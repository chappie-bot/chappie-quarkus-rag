# chappie-quarkus-rag

## Find all documentation adoc files:

```
java -jar target/chappie-quarkus-rag-999-SNAPSHOT.jar find --repo-root /tmp/quarkus-3.26.3 --quarkus-version 3.26.3 --out /tmp/quarkus-3.26.3-docs.json

```

## Enrich with some more data:

```
java -jar target/chappie-quarkus-rag-999-SNAPSHOT.jar manifest-enrich --repo-root /tmp/quarkus-3.26.3 --in /tmp/quarkus-3.26.3-docs.json --out /tmp/quarkus-3.26.3-docs.enriched.json
```

## Injest and create portable image

```
java -jar target/chappie-quarkus-rag-999-SNAPSHOT.jar bake-image --repo-root /tmp/quarkus-3.26.3 --in /tmp/quarkus-3.26.3-docs.enriched.json --quarkus-version 3.26.3 --push --registry-username "phillip-kruger" --registry-password "ghp_???????"
```
