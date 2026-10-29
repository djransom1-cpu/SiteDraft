# SiteDraft cloud setup: accounts, project sync, subscriptions

SiteDraft signs users in with Firebase Authentication, keeps a copy of every project in
Cloud Firestore (`users/{uid}/projects/{name}`), and gates the app behind a Stripe
subscription (or the beta `allowlist`). This file is the checklist of console work that
only the project owner can do, and where the resulting values go. **Nothing below is ever
committed**: the two config files are gitignored and CI recreates them from secrets.

## 1. Firebase console (https://console.firebase.google.com)

**Done 2026-10-29:** the project is `sitedraft-djransom` (display name SiteDraft), with its
Firestore database in `nam5` (US) and the rules and indexes from this repo deployed.
`.firebaserc` makes it the default, so `firebase.cmd deploy` needs no `--project`.
Do not deploy these rules to the CrewSync project: it has its own rules and would break.
(On this PC PowerShell blocks `firebase`; use `firebase.cmd`.) Still to do:

1. ~~Register the Android app~~ (done: `1:551328376608:android:9a328840f44d9140ad1945`, debug SHA-1 and
   SHA-256 added, google-services.json written locally). Still add the release and Play
   App Signing SHA-1s before Google sign-in works in a Play build.
   Was: Project settings > Your apps > Add app > Android.
   Package name `com.example.sitedraft`. Add the debug SHA-1 (needed for Google sign-in):
   `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`
   and later the release keystore's SHA-1. Download `google-services.json` into `app/`.
2. ~~Register a Web app~~ (done: `1:551328376608:web:7ea068f533641644ad1945`, values in the local
   firebase.properties). Was: Add app > Web, nickname "SiteDraft web". Copy the config
   values (`apiKey`, `authDomain`, `projectId`, `storageBucket`, `appId`). The desktop app
   uses this same web app id, as CrewSync does.
3. **Authentication > Get started**, then **Sign-in method**: enable **Email/Password** and **Google**.
   For Google, set the support email; note the **Web client ID** shown under the Google
   provider (Web SDK configuration), it is also `oauth_client[client_type=3]` in
   google-services.json. Put it in firebase.properties as googleWebClientId, then refresh
   the Android config: `firebase.cmd apps:sdkconfig ANDROID 1:551328376608:android:9a328840f44d9140ad1945 --out app/google-services.json`
4. **Authentication > Settings > Authorized domains**: add `djransom1-cpu.github.io`.
5. ~~Firestore Database > Create database~~ (done, nam5).
   Deploy the rules and indexes from this repo: `firebase deploy --only firestore`.
   The indexes matter: "Shared with me" is a collection-group query that fails until the
   `memberEmails` index in firestore.indexes.json has finished building (a few minutes).
6. **Beta testers**: Firestore > `allowlist` collection > add a document whose **id is the
   tester's email in lowercase** (fields are optional, e.g. `note: "beta"`). Those accounts
   pass the gate without a subscription.
7. **Upgrade to the Blaze plan** (pay as you go) - required for Cloud Functions. Then
   `cd functions && npm install && firebase deploy --only functions`.

## 2. Values to put in `firebase.properties` (repo root, gitignored)

```
projectId=...
apiKey=...
authDomain=<projectId>.firebaseapp.com
storageBucket=<projectId>.firebasestorage.app
webAppId=1:1234567890:web:abcdef
googleWebClientId=1234567890-xxxx.apps.googleusercontent.com
functionsRegion=us-central1
```

`firebase.properties.example` shows the shape. The Gradle build turns the file into a
generated `CloudConfig` object; a missing file yields an unconfigured build whose sign-in
screen says so (the app fails closed, nobody gets past the gate).

## 3. Stripe (https://dashboard.stripe.com)

1. Create a **Product** "SiteDraft" with a **monthly recurring price**; copy the price id
   (`price_...`). Optionally give the price a free trial (the gate accepts `trialing`).
2. Developers > API keys: copy the **secret key** (`sk_live_...`, or `sk_test_...` to try it).
3. After the functions are deployed, Developers > Webhooks > Add endpoint:
   URL `https://<functionsRegion>-<projectId>.cloudfunctions.net/stripeWebhook`,
   events `checkout.session.completed`, `customer.subscription.created`,
   `customer.subscription.updated`, `customer.subscription.deleted`.
   Copy the **signing secret** (`whsec_...`).
4. Store the three values as Cloud Functions secrets (prompted interactively, never in git):
   ```
   firebase functions:secrets:set STRIPE_SECRET_KEY
   firebase functions:secrets:set STRIPE_WEBHOOK_SECRET
   firebase functions:secrets:set STRIPE_PRICE_ID
   ```
   and redeploy: `firebase deploy --only functions`.
5. Customer portal (Settings > Billing > Customer portal): enable it so "Manage
   subscription" in the app can open it.

## 4. GitHub repository secrets (Settings > Secrets and variables > Actions)

| Secret | Contents | Used by |
| --- | --- | --- |
| `FIREBASE_PROPERTIES_BASE64` | `base64 -w0 firebase.properties` | web.yml, desktop-release.yml, android-release.yml |
| `GOOGLE_SERVICES_JSON_BASE64` | `base64 -w0 app/google-services.json` | android-release.yml |

(The existing `ANDROID_KEYSTORE_*` secrets stay as they are.)

## 5. How the gate decides

After sign-in the app reads `users/{uid}` and `allowlist/{email}`:

- `subscription.status` is `active` or `trialing` -> allowed
- the email has an `allowlist` document -> allowed
- otherwise the Subscribe screen, with a button that opens Stripe Checkout
- if Firestore cannot be reached (offline), the last allowed answer is reused for 7 days

`users/{uid}.subscription` is written only by the `stripeWebhook` function (the security
rules reject client writes to that field).

## 6. What happens to projects made before accounts

Nothing is deleted. Project files stay where they always were (tablet app storage,
Documents/SiteDraft on the desktop, browser storage on the web). At the first sign-in each
device uploads every project the cloud does not have yet. If two devices both have a
never-synced project with the same name (typically "Untitled Project"), the first one
uploaded keeps the name and the other device keeps its drawing as "<name> (<device>)",
which is uploaded too. After that, the newest save of a project wins everywhere.

## 7. Sign-in methods per platform

| Platform | Email + password | Google |
| --- | --- | --- |
| Android | yes | yes (Credential Manager; needs `googleWebClientId` and the SHA-1s) |
| Desktop | yes | not yet |
| Web | yes | not yet |

## 8. Sharing projects

In the Projects panel, **Share** under the current project opens a dialog where you add
someone's email as **Can edit** or **View only**, change their role, or remove them.

- The other person signs in to SiteDraft with that email and finds the project under
  **Shared with me**, named "<project> (<owner>)". Nobody is emailed by SiteDraft, so tell them.
- Their email must be **verified**. New email/password accounts are sent a verification
  link at sign-up; the Projects panel has "Send email" and "I've verified" buttons.
  Google accounts are verified already.
- Each person needs their own access (a subscription or an `allowlist` entry); sharing a
  project does not let someone past the gate.
- Editors' saves go to the owner's copy, newest save wins. Viewers can look and draw
  locally, but their changes are never uploaded and are replaced by the owner's copy at the
  next sync.
- Removing someone drops the project from their list at their next sync. The file stays on
  their device.
- The security rules enforce all of this server-side (firestore.rules): only the owner can
  change who a project is shared with, and only verified emails count.

## 9. Admin (the owner) and access codes

**Admin.** An admin always gets in, and Settings > Account shows an **Admin** button with
three tabs: every account (email, subscription or code, last seen, device), the beta email
allowlist (add/remove), and access codes (create, switch on/off, how many accounts used each).
Admins see accounts only, never anyone's drawings. To make yourself admin, once:

1. Verify your email (the link in the verification email SiteDraft sent at sign-up; the
   Subscribe screen can send it again). Admin rights only count for a verified email.
2. Firestore > **+ Start collection** > ID `admins` > document ID = your email in lowercase
   (e.g. `djransom1@gmail.com`) > any field (e.g. `note` = `owner`) > Save.
3. In the app, tap **I have subscribed, check again** (or sign in again).

Admins can only be added in the console; nobody can make themselves admin from the app.

**Access codes.** Testers tap **Have a code?** on the Subscribe screen and type a code. Create
codes on the Admin screen's Codes tab (or in the console: collection `accessCodes`, document
ID = the code in capitals, 3 to 32 letters, digits or dashes; field `active` = true). Switching
a code off shuts out everyone who used it, at their next check. For an end date, add a field
named `expires` of type **timestamp** to the code in the console. The security rules check
every code, so the list of codes is never visible to users.
