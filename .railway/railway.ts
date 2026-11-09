import { defineRailway, github, postgres, project, service } from "railway/iac";

const repo = "Narote-Dev/thaishopfun-oms";

// Staging trial project (low cost): one Postgres + backend + mock-tsf. SPA on Vercel.
// Apply once with `railway config apply` after `railway login` and `railway link`.
// Per-service Docker settings are mirrored in backend/railway.json and mock-tsf/railway.json
// (Railway config as code: https://docs.railway.com/reference/config-as-code).
export default defineRailway(() => {
  const db = postgres("Postgres");

  const backend = service("backend", {
    source: github(repo, { branch: "main", rootDirectory: "backend" }),
    healthcheck: "/actuator/health",
    healthcheckTimeout: 120,
    env: {
      SPRING_PROFILES_ACTIVE: "staging",
      DATABASE_URL: db.env.DATABASE_URL,
      // Set DATABASE_PASSWORD in the dashboard after ALTER ROLE oms_app (docs/deploy/STAGING.md).
      DATABASE_USERNAME: "oms_app",
      FLYWAY_USER: db.env.PGUSER,
      FLYWAY_PASSWORD: db.env.PGPASSWORD,
    },
  });

  const mockTsf = service("mock-tsf", {
    source: github(repo, { branch: "main", rootDirectory: "." }),
    healthcheck: "/actuator/health",
    healthcheckTimeout: 120,
    env: {
      PORT: "8090",
    },
  });

  return project("thaishopfun-oms-staging", {
    resources: [db, backend, mockTsf],
  });
});
