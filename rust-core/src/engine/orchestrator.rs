use super::recovery::{FailureKind, Health, ModelState};
use crate::errors::CoreError;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Candidate {
    pub id: String,
    pub role: String,
    pub context: u32,
    pub memory_mb: u64,
}
#[derive(Debug, Deserialize)]
pub struct PlanRequest {
    pub primary_id: String,
    #[serde(default)]
    pub current_id: String,
    pub candidates: Vec<Candidate>,
    pub budget_mb: u64,
    pub now_ms: u64,
    #[serde(default)]
    pub prefer_loaded: bool,
    #[serde(default)]
    pub busy: bool,
    #[serde(default)]
    pub low_memory: bool,
}
#[derive(Debug, Serialize)]
pub struct Decision {
    pub candidate: Option<Candidate>,
    pub reason: &'static str,
    pub retry_at_ms: u64,
}
#[derive(Debug, Deserialize)]
pub struct Outcome {
    pub id: String,
    pub now_ms: u64,
    #[serde(default)]
    pub success: bool,
    pub failure: Option<FailureKind>,
    #[serde(default)]
    pub budget_mb: u64,
    #[serde(default)]
    pub retry_after_ms: u64,
}
#[derive(Default)]
pub struct Orchestrator {
    health: BTreeMap<String, Health>,
    pub fallback_events: u64,
    pub recovery_attempts: u64,
    pub failures: u64,
    last_choice: String,
    pub active_id: String,
}
impl Orchestrator {
    pub fn plan(&mut self, req: PlanRequest) -> Result<Decision, CoreError> {
        if req.candidates.len() > 16
            || req.primary_id.len() > 256
            || req.current_id.len() > 256
            || req.candidates.iter().any(|c| {
                c.id.is_empty()
                    || c.id.len() > 256
                    || c.context < 128
                    || c.context > 131072
                    || !matches!(
                        c.role.as_str(),
                        "PRIMARY" | "PRIMARY_STEPPED_DOWN" | "SECONDARY_FALLBACK"
                    )
            })
        {
            return Err(CoreError::InvalidRequest);
        }
        if req.busy {
            return Ok(Decision {
                candidate: None,
                reason: "ACTIVE_REQUEST",
                retry_at_ms: 0,
            });
        }
        if req.low_memory {
            return Ok(Decision {
                candidate: None,
                reason: "MEMORY_PRESSURE",
                retry_at_ms: 0,
            });
        }
        let usable = |c: &&Candidate| {
            c.memory_mb <= req.budget_mb
                && self
                    .health
                    .get(&c.id)
                    .is_none_or(|h| h.usable(req.now_ms, req.budget_mb, c.memory_mb))
        };
        let primary = req
            .candidates
            .iter()
            .filter(|c| c.id == req.primary_id)
            .find(usable);
        let fallback = req
            .candidates
            .iter()
            .filter(|c| c.id != req.primary_id)
            .find(usable);
        let recovering = !req.current_id.is_empty() && req.current_id != req.primary_id;
        let selected = primary
            .filter(|c| {
                !recovering
                    || !req.prefer_loaded
                    || req.budget_mb >= c.memory_mb.saturating_add(192)
                    || fallback.is_none()
            })
            .or(fallback);
        let retry_at = self.health.get(&req.primary_id).map_or(0, |h| h.retry_at);
        let Some(chosen) = selected else {
            return Ok(Decision {
                candidate: None,
                reason: if primary.is_some() {
                    "RECOVERY_HEADROOM"
                } else {
                    "NO_USABLE_MODEL"
                },
                retry_at_ms: retry_at,
            });
        };
        let reason = if chosen.id == req.primary_id {
            if recovering {
                self.recovery_attempts = self.recovery_attempts.saturating_add(1);
                "PRIMARY_RECOVERY"
            } else {
                "PRIMARY_PREFERRED"
            }
        } else {
            if self.last_choice != chosen.id {
                self.fallback_events = self.fallback_events.saturating_add(1);
            }
            if primary.is_some() {
                "RECOVERY_HEADROOM"
            } else if retry_at > req.now_ms {
                "PRIMARY_COOLDOWN"
            } else {
                "PRIMARY_DOES_NOT_FIT"
            }
        };
        self.last_choice = chosen.id.clone();
        Ok(Decision {
            candidate: Some(chosen.clone()),
            reason,
            retry_at_ms: retry_at,
        })
    }
    pub fn outcome(&mut self, event: Outcome) -> Result<ModelState, CoreError> {
        if event.id.is_empty() || event.id.len() > 256 {
            return Err(CoreError::InvalidRequest);
        }
        if !self.health.contains_key(&event.id)
            && self.health.len() >= 64
            && let Some(oldest) = self
                .health
                .iter()
                .min_by_key(|(_, h)| h.touched)
                .map(|(id, _)| id.clone())
        {
            self.health.remove(&oldest);
        }
        let h = self.health.entry(event.id.clone()).or_default();
        if event.success {
            *h = Health {
                state: ModelState::Ready,
                touched: event.now_ms,
                ..Health::default()
            };
            self.active_id = event.id.clone();
        } else if let Some(kind) = event.failure {
            if !kind.neutral() {
                self.failures = self.failures.saturating_add(1);
                h.failures = h.failures.saturating_add(1);
                h.kind = Some(kind);
                h.touched = event.now_ms;
                h.budget_at_failure = event.budget_mb;
                h.state = if kind.permanent() {
                    ModelState::Unavailable
                } else {
                    ModelState::Cooldown
                };
                h.retry_at = event.now_ms.saturating_add(kind.cooldown(
                    h.failures,
                    &event.id,
                    event.retry_after_ms,
                ));
            } else if h.state == ModelState::Initializing {
                h.state = ModelState::Uninitialized;
            }
        }
        // No status/failure field is unknown health, never a permanent failure.
        Ok(h.state)
    }
    pub fn reset(&mut self, id: &str) {
        self.health.remove(id);
    }
    pub fn unloaded(&mut self, id: &str) {
        if self.active_id == id {
            self.active_id.clear();
        }
        if let Some(h) = self.health.get_mut(id)
            && h.state == ModelState::Ready
        {
            h.state = ModelState::Uninitialized;
        }
        // Keep failure cooldowns when a failing primary is unloaded for fallback.
    }
    pub fn loading(&mut self, id: String, now: u64) -> Result<(), CoreError> {
        self.outcome(Outcome {
            id: id.clone(),
            now_ms: now,
            success: false,
            failure: None,
            budget_mb: 0,
            retry_after_ms: 0,
        })?;
        if let Some(h) = self.health.get_mut(&id) {
            h.state = ModelState::Initializing;
        }
        Ok(())
    }
    pub fn status(&self, id: &str) -> serde_json::Value {
        let empty = Health::default();
        let h = self.health.get(id).unwrap_or(&empty);
        serde_json::json!({"state":h.state,"retry_at_ms":h.retry_at,"failure":h.kind,"fallback_events":self.fallback_events,
            "recovery_attempts":self.recovery_attempts,"failures":self.failures,"tracked_models":self.health.len()})
    }
}
