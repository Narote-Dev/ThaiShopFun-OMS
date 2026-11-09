#!/usr/bin/env bash
set -euo pipefail
# One-off demo catalog seed on Railway (staging profile). Requires env from docs/deploy/STAGING.md.
export SPRING_PROFILES_ACTIVE=staging
export OMS_STAGING_ADMIN_SEED_DEMO=true
cd "$(dirname "$0")/../backend"
exec java -jar target/oms.jar
