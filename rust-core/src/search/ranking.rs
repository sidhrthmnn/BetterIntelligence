use crate::errors::CoreError;
use serde::Deserialize;
use std::collections::BTreeSet;

#[derive(Deserialize)]
pub struct Passage {
    pub cost: u32,
    pub score: f64,
    #[serde(default)]
    pub coverage: Vec<u32>,
    #[serde(default)]
    pub subjects: Vec<u32>,
    #[serde(default = "yes")]
    pub allowed: bool,
}
fn yes() -> bool {
    true
}
#[derive(Deserialize)]
pub struct RankingRequest {
    pub passages: Vec<Passage>,
    pub budget: u32,
    #[serde(default)]
    pub initial_cost: u32,
    pub coverage_goals: usize,
    pub subject_goals: usize,
}
pub fn select(req: RankingRequest) -> Result<Vec<usize>, CoreError> {
    if req.passages.len() > 350
        || req.budget > 65536
        || req.coverage_goals > 64
        || req.subject_goals > 64
        || req.passages.iter().any(|p| {
            !p.score.is_finite()
                || p.cost > 1_000_000
                || p.coverage.len() > 64
                || p.subjects.len() > 64
        })
    {
        return Err(CoreError::LimitExceeded);
    }
    let mut remaining: BTreeSet<usize> = (0..req.passages.len())
        .filter(|&i| req.passages[i].allowed)
        .collect();
    let mut selected = BTreeSet::new();
    let mut covered = BTreeSet::<u32>::new();
    let mut subjects = BTreeSet::<u32>::new();
    let mut size = req.initial_cost;
    while !remaining.is_empty() && selected.len() < 7 {
        let score = |i: usize| {
            let p = &req.passages[i];
            p.score
                + p.coverage.iter().filter(|v| !covered.contains(*v)).count() as f64 * 15.0
                + p.subjects.len() as f64 * 6.0
                + p.subjects.iter().filter(|v| !subjects.contains(*v)).count() as f64 * 20.0
        };
        let i = *remaining
            .iter()
            .max_by(|&&a, &&b| score(a).total_cmp(&score(b)).then_with(|| b.cmp(&a)))
            .ok_or(CoreError::InternalFailure)?;
        remaining.remove(&i);
        let p = &req.passages[i];
        if size.saturating_add(p.cost).saturating_add(2) > req.budget && !selected.is_empty() {
            continue;
        }
        selected.insert(i);
        covered.extend(p.coverage.iter().copied());
        subjects.extend(p.subjects.iter().copied());
        size = size.saturating_add(p.cost).saturating_add(2);
        if covered.len() == req.coverage_goals
            && subjects.len() == req.subject_goals
            && selected.len() >= 3
            && size >= 650.min(req.budget / 2)
        {
            break;
        }
    }
    Ok(selected.into_iter().collect())
}
