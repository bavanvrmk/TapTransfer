import http.client
import json
import os
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer
import server

class SyncTest(unittest.TestCase):
    def test_concurrent_writers_and_receipt_access(self):
        with tempfile.TemporaryDirectory() as tmp:
            server.DB = tmp + "/test.db"
            os.environ["TRIP_CREATE_KEY"] = "test-creation-key-for-local-test"
            api=ThreadingHTTPServer(("127.0.0.1",0),server.Handler)
            threading.Thread(target=api.serve_forever,daemon=True).start()
            try:
                def call(method,path,key,payload=None,tag=None):
                    c=http.client.HTTPConnection("127.0.0.1",api.server_port)
                    headers={"Authorization":"Bearer "+key}
                    if tag: headers["If-Match"]=tag
                    c.request(method,path,json.dumps(payload) if payload else None,headers)
                    r=c.getresponse(); result=(r.status,r.getheader("ETag"),r.read());c.close();return result
                trip=dict(version=1,id=str(uuid.uuid4()),name="Goa",members=["A","B"],keeper="B",entries=[])
                status,tag,res=call("POST","/trips",os.environ["TRIP_CREATE_KEY"],trip)
                self.assertEqual(status,201)
                key=json.loads(res)["token"];path="/trips/"+trip["id"]
                self.assertEqual(call("GET",path,"invalid")[0],403)
                self.assertEqual(json.loads(call("GET",path,key)[2]),trip)
                row=dict(id=str(uuid.uuid4()),kind="Expense",at=1,method="Cash",by="A",note="Cafe",paid={"A":500},owed={"B":500},receipt="JVBERi0xLjQ=")
                updated={**trip,"entries":[row]}
                self.assertEqual(call("PUT",path,key,updated,tag)[0],200)
                self.assertEqual(call("PUT",path,key,trip,tag)[0],409)
                status,tag,res=call("GET",path,key)
                self.assertEqual(json.loads(res)["entries"][0]["receipt"],row["receipt"])
                self.assertEqual(call("PUT",path,key,trip,tag)[0],409)
                invalid={**row,"id":str(uuid.uuid4()),"owed":{"B":600}}
                self.assertEqual(call("PUT",path,key,{**trip,"entries":[row,invalid]},tag)[0],400)
            finally:
                api.shutdown();api.server_close()
