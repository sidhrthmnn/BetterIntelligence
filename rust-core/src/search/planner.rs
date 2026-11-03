use serde::{Deserialize, Serialize};

#[derive(Default, Deserialize)]
pub struct RouteSignals {
    #[serde(default)]
    pub permitted: bool,
    #[serde(default)]
    pub requested: bool,
    #[serde(default)]
    pub private_input: bool,
    #[serde(default)]
    pub explicit_offline: bool,
    #[serde(default)]
    pub calculation: bool,
    #[serde(default)]
    pub transformation: bool,
    #[serde(default)]
    pub force_web: bool,
    #[serde(default)]
    pub clarification: bool,
}
#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QueryRoute {
    DirectLlm,
    WebSearch,
    Clarification,
}
pub fn route(s: RouteSignals) -> QueryRoute {
    if !s.permitted
        || s.private_input
        || s.explicit_offline
        || s.calculation
        || s.transformation && !s.force_web
    {
        QueryRoute::DirectLlm
    } else if s.requested && s.clarification {
        QueryRoute::Clarification
    } else if s.requested {
        QueryRoute::WebSearch
    } else {
        QueryRoute::DirectLlm
    }
}
