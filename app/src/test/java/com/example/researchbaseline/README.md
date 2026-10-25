# Frozen beta5 retrieval baseline

ResearchQuery, HtmlDiscovery, WebPageExtractor and PublicWebResearch are test-only snapshots from commit `842b46eb72a1988cdf9d97bc99752ad2ac19056a`.

Adaptations: package renamed to `com.example.researchbaseline`, shared grounding types imported, and the duplicate WebDocument/WebFetcher declarations removed so identical fixture documents and current transport can be passed to both implementations. Query interpretation, result ordering, extraction, filtering and selection algorithms remain the beta5 implementation. Current HTTPS/DNS/robots transport and evidence-prompt/citation checks are shared; live results compare retrieval pipelines, not the entire old application binary.

This directory is compiled only for JVM and opt-in Android instrumentation comparisons. It is not part of the APK/AAB production sources. Do not update the snapshot to make benchmarks pass. Any future baseline change must identify its commit and adaptations.
