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
    if req.passages.len() > 1024
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
        if size.saturating_add(p.cost).saturating_add(2) > req.budget {
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

#[cfg(test)]
mod extraction_tests {
    use super::*;
    fn request(count: usize) -> RankingRequest {
        RankingRequest { passages: (0..count).map(|_| Passage { cost: 20, score: 1.0, coverage: vec![], subjects: vec![], allowed: true }).collect(), budget: 100, initial_cost: 0, coverage_goals: 0, subject_goals: 0 }
    }
    #[test]
    fn skips_oversized_first_passage_instead_of_exhausting_packet() {
        let mut r = request(2); r.passages[0].cost = 200; r.passages[0].score = 100.0;
        assert_eq!(select(r).unwrap(), vec![1]);
    }
    #[test]
    fn bounded_larger_document_keeps_late_ranked_answer() {
        let mut r = request(1024); r.passages[1000].score = 999.0;
        assert!(select(r).unwrap().contains(&1000));
        assert!(select(request(1025)).is_err());
    }
}
