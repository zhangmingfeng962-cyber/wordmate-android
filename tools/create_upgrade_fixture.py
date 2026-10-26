import datetime,hashlib,json,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parent
day=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()-datetime.date(1970,1,1)).days
image=(root/"fixtures/upgrade-image.png").read_bytes()
name="media/"+hashlib.sha256(image).hexdigest()+".jpg"
card={"id":"apple","spelling":"apple","meaning":"自定义苹果","meanings":json.dumps(["自定义苹果"],ensure_ascii=False),
      "phonetic":"ˈæpəl","exampleEn":"I keep an apple.","exampleZh":"保留的自定义例句。",
      "image":name,"imageCredit":"升级测试图片","segments":json.dumps(["ap","ple"]),"imageQuery":"","imagePrompt":"",
      "sources":"用户手动资料 · 升级测试","error":"","createdAt":1710000000000,"revision":4}
review={"wordId":"apple","started":True,"meaningStep":2,"spellingStep":1,"meaningDue":day-1,"spellingDue":day-1,
        "lastDay":day-1,"meaningMisses":2,"spellingMisses":3}
session={"day":day,"items":json.dumps([{"wordId":"apple","newWord":False,"retry":False}]),"index":0,"phase":"spelling",
         "options":json.dumps(["自定义苹果","道路","窗户","勇敢"],ensure_ascii=False),"chosen":"自定义苹果","typed":"app",
         "assisted":False,"meaningCorrect":True,"spellingCorrect":False,"completed":0,"correct":0}
payload={"version":1,"words":[card],"reviews":[review],"attempts":[{"id":"baseline-attempt","wordId":"apple","day":day-1,
  "meaningCorrect":True,"spellingCorrect":False,"retry":False,"assisted":False,"submitted":"aple","timestamp":1710000001000}],
  "sessions":[session],"settings":{"newLimit":12,"reviewLimit":23,"accent":"en-US","reminder":False,"reminderHour":20,
  "reminderMinute":0,"aiEnabled":False,"textBase":"https://example.invalid/v1","textModel":"upgrade-test","imageBase":"","imageModel":""},
  "files":{name:hashlib.sha256(image).hexdigest()}}
with zipfile.ZipFile(root/"v1-upgrade-fixture.zip","w",zipfile.ZIP_DEFLATED) as z:
 z.writestr("manifest.json",json.dumps(payload,ensure_ascii=False))
 z.writestr(name,image)
print("Created v1 backup for real in-place upgrade, day="+str(day))
