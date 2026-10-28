# Trip sharing

Trip money is opt-in under Home → Edit or Set up profile → Trip money. Turn it off to hide the Trips tab. Locally saved trips remain on the phone.

## Start a trip
Enter the trip name, a comma-separated roster of 2–30 unique names and the cash keeper. Use the same group file or invite across phones. The roster and keeper are fixed for this version.

Record money that has actually moved:
- Contribution transfers a member's cash or UPI payment into the wallet the keeper holds.
- Expense records who funded a purchase. Select one payer, multiple payers or the group wallet. Choose equal, exact, percentage, weighted or item-by-item allocation. Leave excluded participants blank. Indivisible paise go to the largest fractional shares, with ties broken by member name.
- Withdrawal returns money from the wallet. Repayment records a direct transfer between participants. Neither increases trip spending.
- Positive balances are amounts due to members. Negative balances are amounts owed. Return remaining wallet cash before using the final repayment suggestions.
- Activity shows contributors, allocations and receipts. Reverse a mistaken entry and add a replacement. A reversal cannot overdraw the wallet.

PDF, JPEG and PNG attachments may be up to 2 MiB each. Each trip is limited to 2,000 entries and 24 MiB including encoded attachments. Manual foreign exchange converts a single bill total to INR and retains the original amount and rate. CSV reports open in spreadsheet apps.

## Offline group editing
Share a .taptrip file with members, including its receipts. On their phones, members enable Trips and use Import or merge trip from the file picker. Updated copies can be merged in either order. Matching entry IDs are deduplicated. Conflicts and wallet overdrafts are rejected. A trip file and its receipts are readable by anyone who receives them.

## Optional online group server
Requires Python 3.10+ and a user-controlled HTTPS endpoint. There is no preconfigured public service. On the server machine, in PowerShell:

```powershell
cd D:\TapTransfer\trip-server
$env:TRIP_CREATE_KEY = python -c "import secrets; print(secrets.token_urlsafe(32))"
$env:TRIP_DB = "trips.sqlite3"
python server.py
```

The process listens on 127.0.0.1:8787. Put a TLS reverse proxy in front of it using a certificate trusted by the phones. For example, use a Caddy site block with your own DNS name:

```caddy
trips.example.org {
    request_body {
        max_size 24MB
    }
    reverse_proxy 127.0.0.1:8787
}
```

Store the creation key in the host's secret manager for repeat deployments. Give it only to organizers. In Trips → Online group sharing, the organizer enters the HTTPS server URL and creation key once, enables sharing, and sends the resulting private invite. Members paste the complete invite under All trips → Online group sharing → Join shared trip. The invite gives group read and append access, including receipts. Member names on entries are self-reported. Use a trusted group and host. Protect SQLite backups and restrict access to the database file.

Entries are saved locally first. Saving a bill on a linked trip attempts to sync. Use Sync trip now to retrieve other members' changes or retry an interrupted upload. The server uses revisions and append-only checks to prevent lost updates. Repeated imports and syncs preserve unique entries. Automatic background sync and hosted accounts are outside this version.

Run the HTTP integration test with `python -m unittest discover -s trip-server` from the project directory.
