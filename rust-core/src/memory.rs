use crate::errors::CoreError;
use serde::Deserialize;

#[derive(Deserialize)]
pub struct BudgetItem {
    pub cost: u32,
    pub score: i32,
}
#[derive(Deserialize)]
pub struct BudgetRequest {
    pub items: Vec<BudgetItem>,
    pub budget: u32,
    pub anchor: Option<usize>,
    #[serde(default = "limit")]
    pub max_items: usize,
}
fn limit() -> usize {
    64
}
/// Select existing, attributed items by index. Rust never invents summaries or
/// owns a second copy of the database; Android supplies bounded ranking metadata.
pub fn select(req: BudgetRequest) -> Result<Vec<usize>, CoreError> {
    if req.items.len() > 256 || req.budget > 65536 || req.max_items > 64 {
        return Err(CoreError::LimitExceeded);
    }
    let mut budget = req.budget;
    let mut chosen = std::collections::BTreeSet::new();
    let mut add = |i: usize| {
        if let Some(item) = req.items.get(i)
            && chosen.len() < req.max_items
            && item.cost < u32::MAX
            && item.cost < budget
            && chosen.insert(i)
        {
            budget -= item.cost + 1;
        }
    };
    if let Some(i) = req.anchor {
        add(i);
    }
    let mut ranked: Vec<_> = (0..req.items.len()).collect();
    ranked.sort_by(|&a, &b| {
        req.items[b]
            .score
            .cmp(&req.items[a].score)
            .then_with(|| b.cmp(&a))
    });
    for i in ranked {
        add(i);
    }
    Ok(chosen.into_iter().collect())
}
