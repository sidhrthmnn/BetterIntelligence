use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ModelState {
    #[default]
    Uninitialized,
    Initializing,
    Ready,
    Busy,
    Degraded,
    Cooldown,
    Unavailable,
    Failed,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FailureKind {
    Network,
    Dns,
    Authentication,
    RateLimit,
    LoadTransient,
    ModelMissing,
    ContextOverflow,
    Timeout,
    OutOfMemory,
    Provider,
    Unsupported,
    Malformed,
    MalformedResponse,
    Cancelled,
    Busy,
}

impl FailureKind {
    pub fn permanent(self) -> bool {
        matches!(
            self,
            Self::Authentication | Self::ModelMissing | Self::Unsupported | Self::Malformed
        )
    }
    pub fn neutral(self) -> bool {
        matches!(self, Self::Cancelled | Self::Busy | Self::ContextOverflow)
    }
    /// Bounded deterministic jitter avoids synchronized retries without another
    /// dependency, thread, timer or random-device read. Time is monotonic input.
    pub fn cooldown(self, failures: u32, key: &str, retry_after_ms: u64) -> u64 {
        let seed = key
            .bytes()
            .fold(0u64, |h, b| h.wrapping_mul(31).wrapping_add(b as u64));
        let base = if self == Self::OutOfMemory {
            30_000
        } else {
            2_000u64.saturating_mul(1 << failures.saturating_sub(1).min(5))
        };
        if self == Self::RateLimit {
            base.max(retry_after_ms).clamp(2_000, 300_000)
        } else {
            base.saturating_add(seed % 251).min(60_000)
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Health {
    pub state: ModelState,
    pub failures: u32,
    pub retry_at: u64,
    pub budget_at_failure: u64,
    pub kind: Option<FailureKind>,
    pub touched: u64,
}
impl Health {
    pub fn usable(&self, now: u64, budget: u64, needed: u64) -> bool {
        if self.state == ModelState::Unavailable || self.state == ModelState::Failed {
            return false;
        }
        if self.state != ModelState::Cooldown || now >= self.retry_at {
            return true;
        }
        // Retry memory pressure early only after a material resource change and
        // a minimum pause. Never repeatedly allocate during the same failure.
        self.kind == Some(FailureKind::OutOfMemory)
            && budget >= self.budget_at_failure.saturating_add(192)
            && budget >= needed.saturating_add(192)
            && now >= self.touched.saturating_add(2_000)
    }
}
