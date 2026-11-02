# TapTransfer
Android NFC personal UPI request companion. No custody, simulated balance, or bank verification.
## Build
JDK 17, SDK 35, Gradle 8.9. Run:
tools\gradle-8.9\bin\gradle.bat testDebugUnitTest assembleDebug
APK: app/build/outputs/apk/debug/app-debug.apk
## Use
1. Install the updated APK on BOTH NFC/HCE phones.
2. Receiver: Home > Set up profile. Enter display name and personal UPI ID.
3. Receive: enter INR amount and optional note, Create request.
4. Sender: open Pay to start scanning automatically. Keep both phones unlocked and touch NFC areas.
5. Review the request, choose a UPI app, confirm the bank-verified recipient there.
6. Check actual payment status inside the bank/UPI app. App history is unverified.
QR, copy UPI ID, and share link provide alternatives when NFC or UPI intent acceptance fails.
## Protocol v1
SELECT AID F0010203040506 -> TT1 + 9000.
GET CHUNK: 80 10 offsetHi offsetLo 00 -> up to 180 bytes + 6100 (more) or 9000 (complete).
Payload: UTF-8 JSON v,id,upi,name,amount,note,expires. Maximum 2048 bytes.
Requests expire after 120 seconds in TapTransfer. QR/UPI links cannot enforce expiry externally.
Receiver is disarmed in background; leaving Receive clears the request.
## Boundaries
Personal UPI intent acceptance depends on the chosen UPI app. No payment guarantee.
No payment callback is trusted as proof of receipt. No PIN, account balance, or bank credentials are collected.
Names/UPI IDs exchanged through NFC are self-asserted, not cryptographically verified.
History is local, limited to 100 requests and excluded from Android backup.
Duplicate opening of the same request is blocked locally; this is not bank-side idempotency.
A new request may still duplicate a prior payment: check the bank before retrying.
This is a debug build, not a signed production release.
## Device acceptance checklist
- Two-phone transfer, switch roles, 20 taps in each direction.
- Amount 0.01, 10.50; reject 0, negatives, >100000, >2 decimal places.
- Expired request, interrupted tap, NFC disabled, screen locked and app backgrounded.
- QR scan and link sharing; special characters in display names/notes.
- No UPI app installed; chooser cancellation; app return; bank-network failure.
- Confirm no callback produces a verified-success message.
- Restart app and confirm profile/history persistence; inspect font scaling and TalkBack.
## Architecture
Compose UI / lifecycle-aware NFC reader -> request validation -> generic UPI ACTION_VIEW chooser.
HostApduService -> shared armed request -> bounded chunk responses.
SharedPreferences -> profile and request activity. No backend or real-money ledger required for request sharing.

## Version 1.2
- Pay and Profile retain default UPI app selection; Pay starts NFC automatically.
- Profile: System / Light / Dark appearance, persisted locally.
- Receive: decimal keyboard and up to five saved amount presets.
- History: search name/UPI ID/amount, filter requests/payment attempts, tap for details.
- Share individual or filtered activity as text, explicitly labeled NOT proof of payment.
- Clear history requires confirmation and does not reverse payments.
- Amount/note drafts and pending handoff reference survive Activity recreation.
- NFC reader stops in background and resumes on Pay.
QA pending: light/dark contrast, large fonts, rotation during UPI handoff, history sharing/filtering,
preset persistence, and real-device payment-app acceptance.


## Version 1.3
- Receive can split a bill between 2 and 100 people. For an uneven split, pick the appropriate share. Creating each request remains an explicit action.
- Save up to eight amount-and-note templates. Selecting one fills the draft; it can be edited before creating the request.
- Share the QR image through the Android share sheet or keep the phone awake while an active request is displayed.
- Long-press the launcher icon for Pay or Request money. Back from a tab returns home.
- Profile > Screen privacy hides screenshots and recent-app previews.
- Rotation restores valid in-progress requests. Expired requests need to be created again.
- A previously saved payment-app preference stays within that package. Re-select a UPI app explicitly if its payment activity becomes unavailable.

## Release status
The project stays on Android SDK 35. On 2 November 2026 Google Play requires target API 36 for new phone apps.
The release bundle has no production signing configuration. It needs the owner's upload key and Play Console listing, privacy disclosures and release testing before submission.

## Version 1.4 — Trip money
Enable Trip money in Profile to manage group bills, split individual items, track a keeper's wallet, record cash repayments, attach receipts and export a CSV. See [trip usage and server setup](trip-server/README.md) for offline merges and optional online invites.

## Version 1.5 — Google Drive trip folders

1. Enable Trip money in Profile and create a trip. In the Google Drive app, create a folder and give the group Editor access.
2. In the trip, open Sharing → Save trip copy. In the Android document picker choose Drive, the correct Google account and the shared folder. Keep the generated unique filename.
3. Other members use Trips → Import from Drive. Select one or several .taptrip files from that folder for the same trip. Review the number of new entries and confirm.
4. After offline edits, each member saves a fresh copy. On any phone, import the members’ copies, then export the merged result for the group. Drive handles upload and account permissions. Check Drive for upload completion before asking other members to import.
5. If Drive isn't listed in the file picker, use Sharing → Send copy via another app → Drive. For import, download the file from Drive and select it in Downloads.

Sharing status tracks the last successful document export on this device. Imports reject incompatible trips, conflicting IDs, invalid balances and oversized files before modifying the local ledger. Receipts travel with each snapshot. CSV is available on Sharing for spreadsheet review.

## Version 1.6
Open Trips, choose a trip, and tap Add a bill. Choose your name under I am. For an unpaid bill choose To pay, select yourself or another member, and tap Assign bill. For a completed purchase choose Already paid and record the payment method. The default split is equal; tap Split equally to change participants. Other entries and advanced splits opens wallet transfers, multiple payers, exact shares, itemization and foreign-currency options.

Attach a receipt from Camera or Photo or PDF. Photos are resized to fit the existing receipt limit. From Overview → To pay, open an assignment to mark it paid or cancel it. These actions record what members report; confirmation does not initiate a UPI payment. Members exchanging assignments should all use 1.6 or later.

## Version 1.7
Trips can create one collaborative Google Sheet, invite editors, join via its URL and add a shortcut in My Drive. See [account setup and acceptance steps](GOOGLE-SHEETS.md). Tap a trip to open it or hold it for actions. Delete from this phone offers confirmation and undo.
