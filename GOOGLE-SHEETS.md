# One shared Google Sheet per trip

## Google Cloud setup (once for this Android application)
1. In a Google Cloud project that you control, enable Google Sheets API and Google Drive API.
2. Configure the OAuth consent screen and add the intended Google accounts as test users if the project is in Testing.
3. Create an **Android** OAuth client for package `com.taptransfer`. Register the certificate SHA-1 for the installed build. This workstation's debug certificate is `F6:69:A6:CB:0A:A0:7B:E8:8C:81:4B:AE:57:FD:E6:48:11:49:CB:91`. Register the Play app-signing certificate separately for the distributed build.
4. Declare the Google Sheets scope `https://www.googleapis.com/auth/spreadsheets` and the per-file Drive scope `https://www.googleapis.com/auth/drive.file`. Review Google's verification requirements before public distribution. Google Play services identifies the Android client by package name and certificate; no client secret belongs in the APK.

## Organizer
Open Trips, connect Google and choose **Create shared trip**. For an existing trip open **Sharing → Connect Google → Create shared Sheet**. Initialization creates a native spreadsheet in the organizer's Drive, populates its ledger, and remembers its file ID. Retry using the same local trip if interrupted. Under Sharing, invite the member's Google address as an editor; Google emails the invitation. Share Sheet link copies the same document URL into Android's share sheet. Use the app for adding entries and corrections. The Sheet displays the ledger, with hidden data columns preserving receipt chunks and original entry IDs.

## Member
Accept the invitation with the invited account. Paste its docs.google.com/spreadsheets link under **Trips → Join a shared Sheet** and connect Google. In Sharing, **Add shortcut to My Drive** creates a reference to the organizer's spreadsheet. Google Drive also offers Organize → Add shortcut for accounts or policies that prevent the API from creating it. The independent .taptrip copy export remains available under Offline backup.

## Sync behavior
Writes first save locally. With current Google authorization, the open trip checks for updates, syncs new entries and polls roughly once a minute while viewing the trip outside its entry form. Sharing → Sync now performs an immediate refresh. Reconnect if Google requires consent again. App edits append to the canonical ledger using an atomic Sheets batch guarded by a revision. A concurrent writer must re-read and merge. Duplicate entry IDs are deduplicated; incompatible edits or wallet overdrafts surface as errors. Opening the Sheet in Google Sheets is useful for reading or sharing; manually changing machine columns, revision ranges or the tab name can break sync. Editing human-readable labels directly does not change the app's source records.

Removing a trip from a phone requires confirmation. Undo restores the local trip before the deletion dialog is dismissed. Deleting locally preserves the organizer's Sheet and other members' access.

## Acceptance on a real Google account
Create one Sheet, invite a different account, join using that account, add a My Drive shortcut, and confirm both Sheet links contain the same ID. Add independent bills and receipts from both accounts, sync, and compare wallet totals. Also exercise revoked permission, offline retry, account reconnection and cancellation of Google consent.

References: [Google authorization on Android](https://developer.android.com/identity/authorization), [Sheets batch updates](https://developers.google.com/workspace/sheets/api/guides/batch), [Drive shortcuts](https://developers.google.com/workspace/drive/api/guides/shortcuts), [Android core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality).
