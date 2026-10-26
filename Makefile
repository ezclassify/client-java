.PHONY: regen test test-verbose build clean distclean conformance-server api-surface check-api-surface check-architecture check-workflows check-notices notices

# Generation consumes the frozen, provenance-stamped spec bundle. `npm ci`
# installs only the pinned YAML parser. There is deliberately no local
# re-vendor target: vendor/spec/ is written by the spec bot under the
# ezclassify-spec-bot identity, and .github/workflows/vendor-readonly.yml
# rejects any vendor/ commit that is not.
SCHEMAS_DIR := vendor/spec/json-schemas
OUT_DIR     := types/target/generated-sources/java/com/ezclassify/rest/contract
GEN_PKG     := com.ezclassify.rest.contract
# Use the reviewed wrapper by default so developer and CI Maven versions match.
# An installed, compatible Maven remains selectable with make MVN=mvn.
MVN         ?= ./mvnw

.PHONY: docs
# Aggregate both shipped modules, including the generator-owned wire records.
# The forked compile registers generated source roots before the root report.
docs:
	$(MVN) --batch-mode --no-transfer-progress -pl types,client -am javadoc:aggregate

regen:
	npm --prefix tools ci --no-audit --no-fund
	node types/scripts/gen.mjs "$(SCHEMAS_DIR)" "$(OUT_DIR)" "$(GEN_PKG)" vendor/spec/openapi.yaml
	@echo "Regenerated $(OUT_DIR)"

build:
	$(MVN) package --batch-mode --no-transfer-progress -DskipTests

test:
	$(MVN) verify --batch-mode --no-transfer-progress

test-verbose:
	$(MVN) verify --batch-mode -Dsurefire.parallel=none -Dsurefire.useFile=false -Dsurefire.forkCount=1 -Dsurefire.reuseForks=false

clean:
	$(MVN) clean --batch-mode --no-transfer-progress

# distclean: clean plus the toolchain cache a fresh clone does not carry.
# `make regen` reinstalls tools/node_modules from the committed lockfile.
# vendor/spec/ is never swept; it is committed, bot-managed input.
distclean: clean
	rm -rf tools/node_modules

conformance-server: test-conformance-runner
	EZC_SMOKE_URL=$(URL) bash scripts/conformance-server.sh

# The public API is a 1.0-locked contract, snapshotted in api-surface.txt.
# api-surface: rebuild the published modules and regenerate the golden after
#   a reviewed API change. Commit the updated file with the change.
# check-api-surface: fail if the current surface drifted from the golden.
#   This is the CI gate; it needs compiled classes, so build or test first.
api-surface:
	$(MVN) package --batch-mode --no-transfer-progress -DskipTests -pl '!examples/sdk-tour'
	node scripts/api-surface.mjs --write

check-api-surface:
	node scripts/api-surface.mjs --check

check-architecture:
	$(MVN) --batch-mode --no-transfer-progress -pl types,client generate-sources
	node scripts/check-architecture.mjs

check-workflows:
	node --test scripts/check-workflow-location-guards.test.mjs
	node scripts/check-workflow-location-guards.mjs

# check-notices: the notices gate's own tests. They need no maven closure, so
#   they run standalone; CI runs them alongside the workflow-guard tests.
check-notices:
	node --test scripts/gen-notices.test.mjs

# notices: refresh the generated closure section of THIRD-PARTY-NOTICES.md
#   from the current production dependency set. CI runs the same pair in
#   --check mode and fails if the committed file is stale, a dependency license
#   falls outside the allowlist in scripts/gen-notices.mjs, or the curated
#   sections name a different set of components than the closure resolves.
notices:
	$(MVN) org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party -Dlicense.excludedScopes=test,provided --batch-mode --no-transfer-progress -q
	node scripts/gen-notices.mjs --write

# Requires a caller-provided licensed, content-configured REST deployment.
.PHONY: smoke
smoke:
	EZC_SMOKE_URL=$(URL) $(MVN) test -P live -pl client -am --batch-mode --no-transfer-progress

# Pure helper tests for the explicit Python-backed live conformance lane.
.PHONY: test-conformance-runner
test-conformance-runner:
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_conformance_server.py
