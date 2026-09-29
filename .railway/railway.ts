import { defineRailway, github, postgres, project, service } from "railway/iac";

const repo = "Narote-Dev/thaishopfun-oms";

// Staging project. Apply once with `railway config apply` after `railway login`
// and `railway link`. Pushes to main then auto-deploy through the GitHub source.
// Region: set the Railway project to Southeast Asia (Singapore) in the dashboard.
// Do not commit secrets. Database credentials come from the Postgres service.
export default defineRailway(() => {
  const db = postgres("Postgres");

  const backend = service("backend", {
    source: github(repo, { branch: "main", rootDirectory: "backend" }),
    healthcheck: "/actuator/health",
    healthcheckTimeout: 120,
    env: {
      DATABASE_URL: db.env.DATABASE_URL,
      DATABASE_USERNAME: db.env.PGUSER,
      DATABASE_PASSWORD: db.env.PGPASSWORD,
    },
  });

  const frontend = service("frontend", {
    source: github(repo, { branch: "main", rootDirectory: "frontend" }),
    healthcheck: "/",
    env: {
      VITE_OMS_API_BASE_URL: backend.env.RAILWAY_PUBLIC_DOMAIN,
    },
  });

  return project("thaishopfun-oms", {
    resources: [db, backend, frontend],
  });
});
