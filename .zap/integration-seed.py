# ZAP baseline hook (.github/workflows/zap-baseline.yml, `--hook`).
#
# The baseline spider starts at the SPA root and never reaches the machine
# routes of the Bank Integration Hub: they are linked from nowhere and need a
# client credential. This hook puts them in ZAP's site tree when ZAP starts,
# so the PASSIVE scan also covers what an unauthenticated caller of the
# integration API sees (status, security headers, error bodies, cookies,
# information disclosure). Nothing here authenticates or attacks: anonymous
# GETs answer 401, the token and enrolment POSTs carry obviously invalid
# credentials and answer 401 too (docs/integration/developer-guide.md,
# docs/integration/agent.md). No secret is involved.

INTEGRATION_GETS = [
    "/api/integration/v1/templates",
    "/api/integration/v1/jobs/1",
    "/api/integration/v1/jobs/1/results?format=json",
    "/api/integration/v1/jobs/1/errors",
    "/api/integration/v1/documents/1",
    "/api/integration/v1/events?after=0&limit=1",
    "/api/agent/v1/tasks?wait=0",
]

INTEGRATION_POSTS = [
    # (path, content type, body)
    ("/api/integration/oauth/token", "application/x-www-form-urlencoded",
     "grant_type=client_credentials&client_id=mdc_zapbaseline&client_secret=not-a-secret"),
    ("/api/integration/v1/records", "application/json",
     '{"templateId":1,"records":[{"a":"b"}]}'),
    ("/api/agent/v1/enroll", "application/json",
     '{"token":"mda1.1.zap-baseline-not-a-token","name":"zap","version":"0.0.0",'
     '"authMethod":"OAUTH2_CLIENT_CREDENTIALS"}'),
]


def zap_started(zap, target):
    base = target.rstrip("/")
    host = base.split("://", 1)[1].split("/", 1)[0]
    for path in INTEGRATION_GETS:
        zap.core.access_url(url=base + path, followredirects=False)
    for path, content_type, body in INTEGRATION_POSTS:
        raw = (
            "POST {base}{path} HTTP/1.1\r\n"
            "Host: {host}\r\n"
            "Content-Type: {content_type}\r\n"
            "Idempotency-Key: zap-baseline\r\n"
            "Content-Length: {length}\r\n"
            "\r\n{body}"
        ).format(base=base, path=path, host=host, content_type=content_type,
                 length=len(body.encode("utf-8")), body=body)
        zap.core.send_request(request=raw, followredirects=False)
