#[cfg(target_os = "android")]
mod android;
pub mod engine;
pub mod errors;
pub mod memory;
pub mod search;
pub mod streaming;

use errors::CoreError;
use serde::Deserialize;
use serde_json::{Value, json};
use std::sync::{Mutex, OnceLock};

pub const API_VERSION: u32 = 1;
pub const MAX_REQUEST_BYTES: usize = 262_144;
#[derive(Default)]
pub struct Core {
    pub models: engine::orchestrator::Orchestrator,
    pub admission: streaming::Admission,
}
static CORE: OnceLock<Mutex<Core>> = OnceLock::new();
#[derive(Deserialize)]
struct Request {
    version: u32,
    op: String,
    #[serde(default)]
    args: Value,
}
fn decode<T: serde::de::DeserializeOwned>(args: Value) -> Result<T, CoreError> {
    serde_json::from_value(args).map_err(|_| CoreError::InvalidRequest)
}
fn unsigned(args: &Value, key: &str) -> Result<u64, CoreError> {
    args.get(key)
        .and_then(Value::as_u64)
        .ok_or(CoreError::InvalidRequest)
}

pub fn dispatch_in(core: &mut Core, input: &[u8]) -> Result<Value, CoreError> {
    if input.len() > MAX_REQUEST_BYTES {
        return Err(CoreError::LimitExceeded);
    }
    let r: Request = serde_json::from_slice(input).map_err(|_| CoreError::InvalidRequest)?;
    if r.version != API_VERSION {
        return Err(CoreError::UnsupportedVersion);
    }
    match r.op.as_str() {
        "info" => Ok(
            json!({"version":API_VERSION,"runtime":"bi-intelligence/0.1.0","busy":core.admission.busy(),"metrics":core.models.status("")}),
        ),
        "plan_model" => Ok(serde_json::to_value(core.models.plan(decode(r.args)?)?)
            .map_err(|_| CoreError::InternalFailure)?),
        "model_outcome" => Ok(json!({"state":core.models.outcome(decode(r.args)?)?})),
        "model_reset" => {
            let id = r
                .args
                .get("id")
                .and_then(Value::as_str)
                .filter(|v| v.len() <= 256)
                .ok_or(CoreError::InvalidRequest)?;
            core.models.reset(id);
            Ok(json!({"reset":true}))
        }
        "model_unloaded" => {
            let id = r
                .args
                .get("id")
                .and_then(Value::as_str)
                .filter(|v| v.len() <= 256)
                .ok_or(CoreError::InvalidRequest)?;
            core.models.unloaded(id);
            Ok(json!({"unloaded":true}))
        }
        "model_status" => {
            let id = r
                .args
                .get("id")
                .and_then(Value::as_str)
                .filter(|v| v.len() <= 256)
                .ok_or(CoreError::InvalidRequest)?;
            let mut status = core.models.status(id);
            if core.admission.busy() && core.models.active_id == id {
                status["state"] = json!("BUSY");
            }
            Ok(status)
        }
        "model_loading" => {
            let id = r
                .args
                .get("id")
                .and_then(Value::as_str)
                .ok_or(CoreError::InvalidRequest)?
                .to_string();
            core.models.loading(id, unsigned(&r.args, "now_ms")?)?;
            Ok(json!({"state":"INITIALIZING"}))
        }
        "route" => Ok(json!({"route":search::planner::route(decode(r.args)?)})),
        "rank_passages" => Ok(json!({"indices":search::ranking::select(decode(r.args)?)?})),
        "select_context" => Ok(json!({"indices":memory::select(decode(r.args)?)?})),
        "acquire" => Ok(json!({"lease":core.admission.acquire(unsigned(&r.args,"owner")?)})),
        "accept" => Ok(
            json!({"accepted":core.admission.accept(unsigned(&r.args,"owner")?, unsigned(&r.args,"lease")?, unsigned(&r.args,"sequence")?)}),
        ),
        "cancel" => Ok(
            json!({"cancelled":core.admission.cancel(unsigned(&r.args,"owner")?, unsigned(&r.args,"lease")?)}),
        ),
        "finish" => Ok(
            json!({"finished":core.admission.finish(unsigned(&r.args,"owner")?, unsigned(&r.args,"lease")?)}),
        ),
        _ => Err(CoreError::UnknownOperation),
    }
}
pub fn dispatch(input: &[u8]) -> Vec<u8> {
    // No request content, text, paths, model file data or credentials are logged.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut core = CORE
            .get_or_init(|| Mutex::new(Core::default()))
            .lock()
            .map_err(|_| CoreError::StateUnavailable)?;
        dispatch_in(&mut core, input)
    }))
    .unwrap_or(Err(CoreError::InternalFailure));
    match result {
        Ok(value) => json!({"version":API_VERSION,"ok":true,"result":value}),
        Err(error) => json!({"version":API_VERSION,"ok":false,"error":error}),
    }
    .to_string()
    .into_bytes()
}
