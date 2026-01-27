# Experiment Workflow - Quick Guide

This guide shows you how to run ingestion experiments and measure their impact on RAG quality.

## Prerequisites

✅ You already have:
- Baseline captured in `../chappie-server/target/rag-baseline.json`
- JAR file in `/tmp/chappie-quarkus-rag-*.jar`
- Quarkus 3.30.6 sources in `/tmp/quarkus-3.30.6`

## Quick Start - Run Your First Experiment

### Experiment 1: Increase Overlap to 30%

**Hypothesis:** More overlap = better context preservation = higher scores on complex queries

**Current:** chunk-size=1000, chunk-overlap=200 (20%)
**Test:** chunk-size=1000, chunk-overlap=300 (30%)

```bash
cd scripts

# Run the experiment (builds local image, no push)
./experiment-ingest.sh 3.30.6 1000 300 overlap-30pct
```

This will:
1. ✅ Find docs (if not already done)
2. ✅ Enrich manifest (if not already done)
3. ✅ Ingest with 30% overlap
4. ✅ Create Docker image locally

**Wait time:** ~5-10 minutes depending on your machine

Once complete, tag and test:

```bash
# Tag the image
docker tag ghcr.io/quarkusio/chappie-ingestion-quarkus:3.30.6 \
           ghcr.io/quarkusio/chappie-ingestion-quarkus:test-overlap-30pct

# Test it!
cd ../../chappie-server
./mvnw test -Dtest=RagComparisonTest \
  -Drag.image=ghcr.io/quarkusio/chappie-ingestion-quarkus:test-overlap-30pct

# Check results
cat target/rag-comparison-report.md
```

---

## Other Experiments to Try

### Experiment 2: Smaller Chunks (More Precision)

```bash
cd ../chappie-quarkus-rag/scripts
./experiment-ingest.sh 3.30.6 800 200 smaller-chunks

# Tag and test
docker tag ghcr.io/quarkusio/chappie-ingestion-quarkus:3.30.6 \
           ghcr.io/quarkusio/chappie-ingestion-quarkus:test-smaller-chunks
cd ../../chappie-server
./mvnw test -Dtest=RagComparisonTest \
  -Drag.image=ghcr.io/quarkusio/chappie-ingestion-quarkus:test-smaller-chunks
```

**Expected:**
- ✅ Better precision on specific technical queries
- ❌ Might hurt broad conceptual queries

---

### Experiment 3: Larger Chunks (More Context)

```bash
cd ../chappie-quarkus-rag/scripts
./experiment-ingest.sh 3.30.6 1200 200 larger-chunks

# Tag and test
docker tag ghcr.io/quarkusio/chappie-ingestion-quarkus:3.30.6 \
           ghcr.io/quarkusio/chappie-ingestion-quarkus:test-larger-chunks
cd ../../chappie-server
./mvnw test -Dtest=RagComparisonTest \
  -Drag.image=ghcr.io/quarkusio/chappie-ingestion-quarkus:test-larger-chunks
```

**Expected:**
- ✅ Better context for conceptual queries
- ❌ Might reduce precision

---

### Experiment 4: Maximum Overlap (40%)

```bash
cd ../chappie-quarkus-rag/scripts
./experiment-ingest.sh 3.30.6 1000 400 overlap-40pct

# Tag and test
docker tag ghcr.io/quarkusio/chappie-ingestion-quarkus:3.30.6 \
           ghcr.io/quarkusio/chappie-ingestion-quarkus:test-overlap-40pct
cd ../../chappie-server
./mvnw test -Dtest=RagComparisonTest \
  -Drag.image=ghcr.io/quarkusio/chappie-ingestion-quarkus:test-overlap-40pct
```

**Expected:**
- ✅ Even better context preservation
- ⚠️  Slower search (more chunks to compare)

---

## Understanding the Results

After running the comparison test, you'll see output like:

```
✅ [kafka-config] 0.9297 -> 0.9512 (+2.3%)      # Improved!
➖ [rest-json] 0.9127 -> 0.9131 (+0.0%)         # Unchanged
❌ [panache-entity] 0.9017 -> 0.8512 (-5.6%)    # Regressed!

SUMMARY
✅ Improvements:  18
➖ Unchanged:     12
❌ Regressions:   6
```

**Good result:** More improvements than regressions
**Bad result:** Lots of regressions (> 5-6)
**Mixed result:** Similar number of improvements and regressions

---

## Decision Matrix

| Result | Action |
|--------|--------|
| 20+ improvements, 0-3 regressions | ✅ **KEEP IT!** Update baseline |
| 10-20 improvements, 4-8 regressions | 🤔 Review which queries matter most |
| <10 improvements, >10 regressions | ❌ Discard, try different approach |

To keep a good experiment:

```bash
# In chappie-server directory
./mvnw test -Dtest=RagBaselineTest \
  -Drag.image=ghcr.io/quarkusio/chappie-ingestion-quarkus:test-your-experiment

# This becomes your new baseline for future experiments
```

---

## Tips for Success

### 1. Run One Experiment at a Time
Don't change multiple parameters. You won't know what helped.

### 2. Document Everything
Keep notes in a file like this:

```bash
# experiments-log.md

## 2026-01-27: Overlap 30%
- Command: ./experiment-ingest.sh 3.30.6 1000 300 overlap-30pct
- Results: +18 improvements, -6 regressions
- Best improvements: kafka-config (+2.3%), security-oidc (+1.8%)
- Worst regressions: panache-entity (-5.6%)
- Decision: ✅ ACCEPTED - New baseline
```

### 3. Watch for Patterns
- If **Panache queries regress**, chunks might be splitting examples badly
- If **Config queries improve**, overlap is helping with property lists
- If **Everything drops slightly**, embedding or chunking fundamentally changed

### 4. Don't Overfit
The 36 test cases are representative but not exhaustive. If you get suspiciously perfect results, manually test some queries.

---

## Advanced: Testing Future Cases

Try to fix failing cases from `rag-eval-future.json`:

```bash
# Copy a case to rag-eval.json first
# Then run comparison
./mvnw test -Dtest=RagComparisonTest -Drag.image=test-your-experiment

# If the previously-failing case now passes, you fixed it!
```

---

## Current Parameters

**Baseline (3.30.6):**
- Chunk size: 1000
- Chunk overlap: 200 (20%)
- Splitter: recursive
- Model: BgeSmallEnV15Quantized
- Average score: ~0.92

**Sweet Spot Range (generally):**
- Chunk size: 800-1200
- Overlap: 15-40% (150-400 chars)

**Warning Zones:**
- Too small: <500 chars (fragments concepts)
- Too large: >2000 chars (too much noise)
- Too little overlap: <100 chars (lose context)
- Too much overlap: >50% (redundant, slow)

---

## Troubleshooting

**Issue:** "JAR not found in /tmp"
```bash
cd .. && ./mvnw clean package
cp target/chappie-quarkus-rag-999-SNAPSHOT.jar /tmp/
```

**Issue:** "Quarkus repo not found"
```bash
cd scripts
./download.sh 3.30.6
./unzip.sh 3.30.6
```

**Issue:** Docker image not building
```bash
# Check Docker is running
docker ps

# Check base image exists
docker pull pgvector/pgvector:pg16
```

**Issue:** Test fails with dimension mismatch
```bash
# Make sure dimension matches (should be 384)
# This is set in application.properties
```

---

## Next Steps

1. ✅ Run Experiment 1 (overlap 30%)
2. ✅ Review results
3. ✅ Try Experiment 2 if needed
4. ✅ Find the best configuration
5. ✅ Push to official registry

Then explore:
- Code improvements to BakeImageCommand.java
- Different splitting strategies
- Additional metadata extraction
- Content filtering

Good luck! 🚀
