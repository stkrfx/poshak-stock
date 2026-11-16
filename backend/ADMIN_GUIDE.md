# Stock (`com.poshak.stock`) — Hoppscotch Admin Guide (`ADMIN_GUIDE.md`)

All administrative tasks for the **Stock** system (creating users, blocking/unblocking users, changing roles between `admin` and `employee`, inspecting/revoking device sessions, and publishing optional or mandatory APK updates) are performed directly through **Hoppscotch** (`https://hoppscotch.io`). No admin management screens exist inside the Android application.

## 1. Environment & Authentication Setup in Hoppscotch

Create a Hoppscotch Environment named **`Stock Production`** with the following variables:

| Variable Name | Example Value | Purpose | 
| ----- | ----- | ----- | 
| `BASE_URL` | `https://api.yourdomain.com/v1` (or `http://localhost:8080/v1`) | Base API URL | 
| `ADMIN_MASTER_KEY` | `<your ADMIN_MASTER_KEY from .env>` | Direct Hoppscotch authentication without needing a short-lived device JWT | 

### Global Header Configuration

In your Hoppscotch collection or request headers, add:

* **Header Key**: `X-Admin-API-Key`

* **Header Value**: `<<ADMIN_MASTER_KEY>>`

*(Alternatively, you can pass `Authorization: Bearer <admin_access_token>` along with `X-Admin-Secret: <ADMIN_SECRET>` if `ADMIN_SECRET` is configured in `.env`.)*

## 2. Managing Users (Create, List, Block/Unblock, Role Change)

### 2.1 Create an Authorized User (`POST /v1/admin/users`)

Only users registered here with `status = "active"` can pass `/v1/auth/pre-login` and trigger Firebase SMS OTPs.

* **Method**: `POST`

* **URL**: `<<BASE_URL>>/admin/users`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

  * `Content-Type`: `application/json`

* **Body (`application/json`)**:

```
{
  "name": "Mangal",
  "mobile": "+919876543210",
  "role": "employee"
}

```

* **Valid Roles**:

  * `"employee"`: Can search and view stock online/offline, but **cannot** see or filter by `Party` anywhere in the UI or API payloads.

  * `"admin"`: Can see and filter by `Party` in the Search Filter Bottom Sheet, Chips, and Result Table column.

* **Response (`201 Created`)**:

```
{
  "id": "b3d7a1c0-8c3e-4d9a-9f1b-2e5c6a7b8d9e",
  "name": "Mangal",
  "mobile": "+919876543210",
  "role": "employee",
  "status": "active",
  "created_at": "2026-11-16T10:00:00Z",
  "updated_at": "2026-11-16T10:00:00Z"
}

```

### 2.2 List All Users & Active Session Counts (`GET /v1/admin/users`)

* **Method**: `GET`

* **URL**: `<<BASE_URL>>/admin/users`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

* **Response (`200 OK`)**:

```
[
  {
    "id": "b3d7a1c0-8c3e-4d9a-9f1b-2e5c6a7b8d9e",
    "name": "Mangal",
    "mobile": "+919876543210",
    "role": "employee",
    "status": "active",
    "active_session_count": 1,
    "created_at": "2026-11-16T10:00:00Z",
    "updated_at": "2026-11-16T10:00:00Z"
  }
]

```

### 2.3 Block or Unblock a User (`PATCH /v1/admin/users/{id}/status`)

Setting `"status": "blocked"` executes an atomic PostgreSQL transaction that marks the user `"blocked"`, revokes all of their active sessions (`revoked_at = NOW()`), and immediately invalidates the backend session cache. On the user's very next API call or token refresh, the Android app receives `403 Forbidden` with `"error": "account_blocked"` and **wipes all local Room database tables, tokens, and background sync jobs**.

* **Method**: `PATCH`

* **URL**: `<<BASE_URL>>/admin/users/b3d7a1c0-8c3e-4d9a-9f1b-2e5c6a7b8d9e/status`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

  * `Content-Type`: `application/json`

* **Body (`application/json`) — Block User**:

```
{
  "status": "blocked"
}

```

* **Body (`application/json`) — Re-Activate User**:

```
{
  "status": "active"
}

```

### 2.4 Change User Role (`PATCH /v1/admin/users/{id}/role`)

Promotes an employee to `admin` (enabling `Party` visibility) or demotes an admin to `employee` (stripping `Party` visibility).

* **Method**: `PATCH`

* **URL**: `<<BASE_URL>>/admin/users/b3d7a1c0-8c3e-4d9a-9f1b-2e5c6a7b8d9e/role`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

  * `Content-Type`: `application/json`

* **Body (`application/json`)**:

```
{
  "role": "admin"
}

```

## 3. Device Session Inspection & Revocation

### 3.1 List Sessions for a Specific User (`GET /v1/admin/users/{id}/sessions`)

* **Method**: `GET`

* **URL**: `<<BASE_URL>>/admin/users/b3d7a1c0-8c3e-4d9a-9f1b-2e5c6a7b8d9e/sessions`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

* **Response (`200 OK`)**:

```
[
  {
    "id": "7f9c2b10-1234-4a5b-8c9d-0e1f2a3b4c5d",
    "user_id": "b3d7a1c0-8c3e-4d9a-9f1b-2e5c6a7b8d9e",
    "device_id": "a1b2c3d4e5f6",
    "device_name": "Motorola Moto G84",
    "device_type": "android",
    "ip_address": "103.21.58.12",
    "user_agent": "okhttp/4.12.0",
    "last_used_at": "2026-11-16T11:30:00Z",
    "created_at": "2026-11-16T10:05:00Z",
    "revoked_at": null
  }
]

```

### 3.2 Revoke a Specific Device Session (`DELETE /v1/admin/sessions/{session_id}`)

Immediately revokes a single device session. When that device makes its next API call, it receives `401 Unauthorized` with `"error": "session_revoked"`, wiping its local Room cache and returning to the Login screen.

* **Method**: `DELETE`

* **URL**: `<<BASE_URL>>/admin/sessions/7f9c2b10-1234-4a5b-8c9d-0e1f2a3b4c5d`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

* **Response (`200 OK`)**:

```
{
  "message": "session revoked",
  "session_id": "7f9c2b10-1234-4a5b-8c9d-0e1f2a3b4c5d"
}

```

## 4. Publishing In-App Self-Update APK Releases (`POST /v1/admin/releases`)

The Stock Android app checks `GET /v1/app/version?current_version_code=<versionCode>` on startup and foreground resume. To publish a new APK release:

* **Method**: `POST`

* **URL**: `<<BASE_URL>>/admin/releases`

* **Headers**:

  * `X-Admin-API-Key`: `<<ADMIN_MASTER_KEY>>`

* **Body Type**: `multipart/form-data`

| Form Field Key | Type | Example Value (Optional Update) | Example Value (Mandatory Force Update) | Description | 
| ----- | ----- | ----- | ----- | ----- | 
| `apk_file` | **File** | `@app-release.apk` | `@app-release.apk` | Signed release APK file built from Android Studio / Gradle | 
| `version_code` | **Text** | `2` | `3` | Integer matching `versionCode` in `app/build.gradle.kts` | 
| `version_name` | **Text** | `1.1.0` | `1.2.0` | Human-readable version string (`versionName`) | 
| `is_force_update` | **Text** | `false` | `true` | `true` forces all users onto this version immediately | 
| `min_supported_version_code` | **Text** | `1` | `3` | Any app with `versionCode < min_supported_version_code` is forced to update | 
| `release_notes` | **Text** | `Improved offline sync speed.` | `Critical security & schema update.` | Shown inside the Material 3 update dialog | 

* **Response (`201 Created`)**:

```
{
  "id": 1,
  "version_code": 2,
  "version_name": "1.1.0",
  "apk_download_url": "https://api.yourdomain.com/v1/app/download/2",
  "apk_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "release_notes": "Improved offline sync speed.",
  "is_force_update": false,
  "min_supported_version_code": 1,
  "created_at": "2026-11-16T12:00:00Z"
}

```

## 5. Ready-to-Import Hoppscotch Collection JSON

Save the block below as `hoppscotch-stock-admin-collection.json` and import it directly into Hoppscotch (**Collections -> Import -> From JSON**):

```
{
  "v": 2,
  "name": "Stock Admin API (com.poshak.stock)",
  "folders": [],
  "requests": [
    {
      "v": "1",
      "name": "1. Create User",
      "method": "POST",
      "endpoint": "<<BASE_URL>>/admin/users",
      "params": [],
      "headers": [
        { "key": "X-Admin-API-Key", "value": "<<ADMIN_MASTER_KEY>>", "active": true }
      ],
      "preRequestScript": "",
      "testScript": "",
      "auth": { "authType": "none", "authActive": false },
      "body": {
        "contentType": "application/json",
        "body": "{\n  \"name\": \"Mangal\",\n  \"mobile\": \"+919876543210\",\n  \"role\": \"employee\"\n}"
      }
    },
    {
      "v": "1",
      "name": "2. List All Users",
      "method": "GET",
      "endpoint": "<<BASE_URL>>/admin/users",
      "params": [],
      "headers": [
        { "key": "X-Admin-API-Key", "value": "<<ADMIN_MASTER_KEY>>", "active": true }
      ],
      "preRequestScript": "",
      "testScript": "",
      "auth": { "authType": "none", "authActive": false },
      "body": { "contentType": null, "body": null }
    },
    {
      "v": "1",
      "name": "3. Block / Unblock User",
      "method": "PATCH",
      "endpoint": "<<BASE_URL>>/admin/users/<<USER_ID>>/status",
      "params": [],
      "headers": [
        { "key": "X-Admin-API-Key", "value": "<<ADMIN_MASTER_KEY>>", "active": true }
      ],
      "preRequestScript": "",
      "testScript": "",
      "auth": { "authType": "none", "authActive": false },
      "body": {
        "contentType": "application/json",
        "body": "{\n  \"status\": \"blocked\"\n}"
      }
    },
    {
      "v": "1",
      "name": "4. Update User Role (admin / employee)",
      "method": "PATCH",
      "endpoint": "<<BASE_URL>>/admin/users/<<USER_ID>>/role",
      "params": [],
      "headers": [
        { "key": "X-Admin-API-Key", "value": "<<ADMIN_MASTER_KEY>>", "active": true }
      ],
      "preRequestScript": "",
      "testScript": "",
      "auth": { "authType": "none", "authActive": false },
      "body": {
        "contentType": "application/json",
        "body": "{\n  \"role\": \"admin\"\n}"
      }
    },
    {
      "v": "1",
      "name": "5. List User Device Sessions",
      "method": "GET",
      "endpoint": "<<BASE_URL>>/admin/users/<<USER_ID>>/sessions",
      "params": [],
      "headers": [
        { "key": "X-Admin-API-Key", "value": "<<ADMIN_MASTER_KEY>>", "active": true }
      ],
      "preRequestScript": "",
      "testScript": "",
      "auth": { "authType": "none", "authActive": false },
      "body": { "contentType": null, "body": null }
    },
    {
      "v": "1",
      "name": "6. Revoke Device Session",
      "method": "DELETE",
      "endpoint": "<<BASE_URL>>/admin/sessions/<<SESSION_ID>>",
      "params": [],
      "headers": [
        { "key": "X-Admin-API-Key", "value": "<<ADMIN_MASTER_KEY>>", "active": true }
      ],
      "preRequestScript": "",
      "testScript": "",
      "auth": { "authType": "none", "authActive": false },
      "body": { "contentType": null, "body": null }
    }
  ]
}

```