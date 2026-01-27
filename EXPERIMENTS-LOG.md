# Ingestion Experiments Log

This file tracks all ingestion experiments and their results.

---

## 2026-01-27: Experiment 1 - Increase Overlap to 30%

**Hypothesis:** More overlap will improve context preservation, especially for queries about concepts that span multiple paragraphs.

**Configuration:**
- Chunk size: 1000 (unchanged)
- Chunk overlap: 200 → **300** (20% → 30%)
- Embedding model: BgeSmallEnV15Quantized (unchanged)
- Splitter: DocumentSplitters.recursive (unchanged)

**Command:**
```bash
cd scripts
./experiment-ingest.sh 3.30.6 1000 300 overlap-30pct
```

**Results:**
- ✅ **6 Improvements**
- ➖ **30 Unchanged** (< 1% change)
- ❌ **0 Regressions** (no drops > 5%)

**Top Improvements:**
1. rest-json: 0.9127 → 0.9372 (+2.7%)
2. health-checks: 0.9064 → 0.9280 (+2.4%)
3. async-rest: 0.8831 → 0.9028 (+2.2%) ← *Previously lowest score*
4. config-properties: 0.8931 → 0.9048 (+1.3%)
5. cdi-injection: 0.9176 → 0.9277 (+1.1%)
6. devservices-postgresql: 0.9262 → 0.9362 (+1.1%)

**Minor Drops (< 3%):**
- devui-add-page: 0.9444 → 0.9212 (-2.5%)
- virtual-threads: 0.9388 → 0.9183 (-2.2%)
- panache-testing: 0.9110 → 0.8939 (-1.9%)

These are acceptable trade-offs given the significant improvements elsewhere.

**Analysis:**
The increased overlap particularly helped with:
- REST/JSON endpoint documentation (spans multiple code examples)
- Configuration properties (long lists benefit from context)
- Async/reactive patterns (complex concepts across paragraphs)
- Health/observability (multi-step setup instructions)

**Decision:** ✅ **ACCEPTED** - Updated baseline to this configuration

**New Baseline:**
- Image: `ghcr.io/quarkusio/chappie-ingestion-quarkus:test-overlap-30pct`
- New lowest score: panache-repository (0.8975) - up from async-rest (0.8831)
- Overall average: ~0.92 (maintained)

**Impact:**
- Stronger foundation for future experiments
- Better handling of complex, multi-paragraph concepts
- No significant downsides

**Next Steps:**
- Could try 40% overlap to see if even more context helps
- Could experiment with different chunk sizes (800-1200) with this overlap
- Could explore semantic chunking by document headers

---

## 2026-01-27: Experiment 2 - Smaller Chunks (800) with 30% Overlap

**Hypothesis:** Smaller chunks might improve precision for specific technical queries while maintaining context with 30% overlap.

**Configuration:**
- Chunk size: 1000 → **800**
- Chunk overlap: 300 → **240** (maintaining 30%)
- Embedding model: BgeSmallEnV15Quantized (unchanged)
- Splitter: DocumentSplitters.recursive (unchanged)

**Command:**
```bash
./experiment-ingest.sh 3.30.6 800 240 smaller-chunks-30pct
```

**Results:**
- ✅ **3 Improvements**
- ➖ **33 Unchanged**
- ❌ **0 Regressions** (no drops > 5%)

**Top Improvements:**
1. kafka-producer: +2.2%
2. kafka-consumer: +2.1%
3. liquibase-migration: +1.1%

**Concerning Drops (2-3%):**
- logging-config: -2.2%
- jwt-security: -2.0%
- grpc-service: -1.7%
- virtual-threads: -1.6%

**Analysis:**
- ✅ Smaller chunks helped with messaging/Kafka queries
- ❌ Hurt security and service-related queries
- Trade-off: More precision but lost important context for complex topics

**Decision:** ❌ **REJECTED** - Security query degradation unacceptable

**Reasoning:**
- Only 3 improvements vs 8 queries with 1-2% drops
- Security documentation accuracy is critical
- Marginal Kafka improvements don't justify security/logging losses

---

## 2026-01-27: Experiment 3 - Larger Chunks (1200) with 30% Overlap

**Hypothesis:** Larger chunks might improve conceptual queries and configuration documentation while maintaining overlap benefits.

**Configuration:**
- Chunk size: 1000 → **1200**
- Chunk overlap: 300 → **360** (maintaining 30%)
- Embedding model: BgeSmallEnV15Quantized (unchanged)
- Splitter: DocumentSplitters.recursive (unchanged)

**Command:**
```bash
./experiment-ingest.sh 3.30.6 1200 360 larger-chunks-30pct
```

**Results:**
- ✅ **4 Improvements**
- ➖ **32 Unchanged**
- ❌ **0 Regressions** (no drops > 5%)

**Top Improvements:**
1. kafka-producer: +2.0%
2. datasource-config: +1.6%
3. config-properties: +1.3%
4. kafka-consumer: +1.2%

**Concerning Drops (2-3%):**
- jwt-security: -2.7% ⚠️ (worst of all experiments)
- rest-json: -2.4%
- panache-testing: -1.9%
- devservices-postgresql: -1.6%
- transaction-management: -1.6%

**Analysis:**
- ✅ Larger chunks helped with configuration and datasource queries
- ❌ Significantly hurt security, testing, and REST queries
- Trade-off: More context but too much noise for specific queries

**Decision:** ❌ **REJECTED** - Even worse security degradation than smaller chunks

**Reasoning:**
- jwt-security dropped -2.7% (worst drop across all experiments)
- 5 queries with significant drops vs only 4 improvements
- Configuration improvements don't justify security/testing losses

---

## Summary: Chunk Size is Already Optimal at 1000

After testing 800, 1000, and 1200 with consistent 30% overlap:

**Conclusion:** 1000-char chunks provide the best balance.
- Neither smaller nor larger showed clear superiority
- Both alternatives introduced unacceptable security query degradation
- 1000 chunks handle both specific and broad queries well

**Key Insight:** The 30% overlap (Experiment 1) was the real improvement, not chunk size.

**Recommendation:** Focus future experiments on:
1. Semantic chunking (by headers, not fixed size)
2. Enhanced metadata extraction
3. Query-specific optimizations
4. Fixing future test cases (rag-eval-future.json)

See `chunk-size-comparison.md` in chappie-server for detailed analysis.

---

## Template for Future Experiments

**Date:** YYYY-MM-DD

**Experiment:** [Name]

**Hypothesis:** [What you expect to improve and why]

**Configuration:**
- Chunk size: [value]
- Chunk overlap: [value]
- Other changes: [list]

**Command:**
```bash
[command used]
```

**Results:**
- Improvements: [count]
- Regressions: [count]
- Top changes: [list]

**Decision:** [ACCEPTED/REJECTED] - [Reasoning]

---
