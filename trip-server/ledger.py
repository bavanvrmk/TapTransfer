"""Validate the INR ledger at the write boundary before accepting a group update."""
import base64
import binascii
import uuid

def check(ok):
    if not ok:
        raise ValueError("Invalid trip ledger")

def validate(data):
    people = data["members"]
    check(isinstance(people,list) and 2 <= len(people) <= 30 and len(set(people)) == len(people))
    check(all(isinstance(p,str) and 0 < len(p.strip()) <= 50 and p != "@wallet" for p in people))
    check(data["keeper"] in people and isinstance(data["name"], str) and 0 < len(data["name"].strip()) <= 80)
    rows = data["entries"]
    check(isinstance(rows,list) and len(rows)<=2000)
    indexed = {e["id"]:e for e in rows}
    check(len(indexed)==len(rows))
    wallet = 0
    corrections=set()
    resolved=set()
    for e in rows:
        check(str(uuid.UUID(e["id"])) == e["id"])
        check(e["by"] in people and isinstance(e["note"],str) and 0<len(e["note"].strip())<=200)
        check(type(e["at"]) is int and 0<=e["at"]<=2**63-1 and e["method"] in ("Cash","UPI","Card","Other"))
        paid, owed=e["paid"], e["owed"]
        for amounts in (paid,owed):
            check(isinstance(amounts,dict) and amounts)
            check(all(k in people+["@wallet"] and type(v) is int and 0<v<=9999999999 for k,v in amounts.items()))
        check(0<sum(paid.values())==sum(owed.values())<=9999999999)
        wallet+=owed.get("@wallet",0)-paid.get("@wallet",0)
        kind=e["kind"]
        if kind=="Assigned":
            check(len(paid)==1 and "@wallet" not in paid and "@wallet" not in owed)
        elif kind in ("Payment","Cancelled"):
            ref=e["reverses"]
            old=indexed.get(ref,{})
            check(old.get("kind")=="Assigned" and old["paid"]==paid and old["owed"]==owed and ref not in resolved)
            resolved.add(ref)
        elif kind=="Expense":
            check("@wallet" not in owed)
        elif kind=="Correction":
            ref=e["reverses"]
            check(ref in indexed and ref not in corrections)
            old=indexed[ref]
            check(old["kind"] not in ("Correction","Assigned","Cancelled") and old["owed"]==paid and old["paid"]==owed)
            corrections.add(ref)
        else:
            check(len(paid)==len(owed)==1 and set(paid)!=set(owed))
            check({"Contribution": "@wallet" in owed,"Withdrawal":"@wallet" in paid,"Repayment":"@wallet" not in paid and "@wallet" not in owed}.get(kind))
        items=e.get("items",[])
        check(isinstance(items,list) and len(items)<=100)
        if items:
            allocations={}
            for item in items:
                check(isinstance(item["label"],str) and 0<len(item["label"].strip())<=80)
                check(isinstance(item["shares"],dict) and item["shares"])
                for k,v in item["shares"].items():
                    check(k in people and type(v) is int and 0<v<=9999999999)
                    allocations[k]=allocations.get(k,0)+v
            check(kind=="Expense" and allocations==owed)
        encoded=e.get("receipt","")
        check(isinstance(encoded,str) and len(encoded)<=2800000)
        if encoded:
            receipt=base64.b64decode(encoded, validate=True)
            check(len(receipt)<=2*1024*1024 and receipt.startswith((b"%PDF-",b"\xff\xd8\xff",b"\x89PNG\r\n\x1a\n")))
    check(wallet>=0)
