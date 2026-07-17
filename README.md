# MoDocument

Document processing API with LLM-powered extraction, template mapping, and validation.

## Setup

1. Configure secrets locally (never commit real keys or passwords):

   **Option A — User secrets (recommended)**

   ```bash
   dotnet user-secrets set "AIfiles:OpenAI:ApiKey" "<your-openai-key>" --project MoDocument.API
   dotnet user-secrets set "ConnectionStrings:Default" "<your-connection-string>" --project MoDocument.API
   ```

   **Option B — Local config file**

   Copy `MoDocument.API/appsettings.Local.json.example` to `MoDocument.API/appsettings.Local.json` and fill in your values. This file is gitignored.

2. Run the API:

   ```bash
   dotnet run --project MoDocument.API
   ```

## LLM endpoint

- `POST /api/aifiles/send` — send a prompt to the AIfiles LLM service
