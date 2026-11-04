// 原本をコピーし、設定変更が図・表・生成入力へ伝わることを検査する。AWS APIは呼ばない。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process'),{createHash}=require('node:crypto');
const {validate,repository}=require('./validate-contract.cjs');
const repo=repository(__dirname),lab=path.resolve(__dirname,'..'),yaml=require(path.join(repo,'web-ui/node_modules/js-yaml'));
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const classpath=path.join(repo,'cli/build/install/cli/lib/*');
assert.ok(fs.existsSync(path.dirname(classpath)),':cli:installDistを先に実行してください。');
fs.mkdirSync(path.join(repo,'scratchpad'),{recursive:true});
const work=fs.mkdtempSync(path.join(repo,'scratchpad/aws-webapp-verify-')),ws=path.join(work,'workspace');
const keep=process.argv.includes('--keep');let calls=0;
fs.cpSync(lab,ws,{recursive:true,filter:p=>p!==path.join(lab,'reports')});
const read=f=>yaml.load(fs.readFileSync(path.join(ws,f),'utf8'),{schema:yaml.CORE_SCHEMA});
const save=(f,v)=>fs.writeFileSync(path.join(ws,f),yaml.dump(v,{noRefs:true,lineWidth:110}));
const resource=id=>'model/resources/'+id+'.yaml';
function cli(args,status=[0]){
 calls++;const r=spawnSync(java,['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',classpath,'dev.toros.yde.cli.MainKt',...args],{cwd:repo,windowsHide:true,encoding:'utf8',timeout:120000,maxBuffer:32*1024*1024});
 fs.writeFileSync(path.join(work,String(calls).padStart(2,'0')+'.txt'),r.stdout||r.stderr||'');
 assert.ifError(r.error);assert.ok(status.includes(r.status),`終了コード ${r.status}: ${args.join(' ')}\n${r.stderr}\n${r.stdout?.slice(0,1500)}`);
 return (r.stdout||'').replace(/^\uFEFF/,'');
}
const json=args=>JSON.parse(cli([...args,'--format','json']));
const view=id=>json(['view',ws,id]);
const exported=file=>yaml.load(cli(['transform',ws,path.join(ws,'transforms/'+file+'.yaml')]),{schema:yaml.CORE_SCHEMA});
function clean(v){assert.deepEqual(v.schemaErrors,[]);assert.deepEqual(v.parseErrors,[]);for(const[k,n]of Object.entries(v.counts))assert.equal(n,0,k);}
function negative(name,file,mutate,expected,status=3){
 const original=fs.readFileSync(path.join(ws,file),'utf8');
 try{const doc=read(file);mutate(doc);save(file,doc);
  const result=JSON.parse(cli(['workspace','validate',ws,'--format','json'],[status]));
  assert.deepEqual(result.schemaErrors,[]);assert.match(JSON.stringify(result),expected);console.log('反例 OK: '+name);
 }finally{fs.writeFileSync(path.join(ws,file),original);}
}
function snapshot(root){const result={};const visit=d=>{for(const entry of fs.readdirSync(d,{withFileTypes:true})){const f=path.join(d,entry.name);if(entry.isDirectory()){if(entry.name!=='reports')visit(f);}else result[path.relative(root,f)]=createHash('sha256').update(fs.readFileSync(f)).digest('hex');}};visit(root);return result;}
const originalHashes=snapshot(lab);
try{
 const contract=validate(ws);assert.deepEqual(contract.errors,[]);assert.equal(contract.resources,211);assert.equal(contract.types,87);
 const validation=json(['workspace','validate',ws]);clean(validation);assert.ok(validation.ledger>200);
 const home=view('aws-home');assert.deepEqual(home.errors,[]);
 assert.equal(home.sections.find(s=>s.id==='resources').count,211);
 for(const section of home.sections){assert.ok(section.count>0,section.id);assert.ok(!section.truncated);assert.ok(!section.withheld?.length);assert.ok(section.rows.every(r=>r.origins?.length));}
 const alb=read(resource('RES-ALB'));
 const albSheet=home.sections.find(s=>s.id==='alb').rows[0];
 for(const[k,v]of Object.entries(alb.properties))assert.deepEqual(albSheet.cells.find(c=>c.label===k)?.value,v,'ALBの全設定表: '+k);
 const overview=view('architecture-flow'),detail=view('entry-flow');
 for(const flow of [overview,detail]){assert.deepEqual(flow.errors,[]);assert.deepEqual(flow.diagnostics,[]);assert.ok(flow.model.nodes.every(n=>n.detail.editable&&n.detail.origins.length));assert.ok(flow.model.edges.every(e=>e.from.state==='resolved'&&e.to.state==='resolved'));}
 assert.equal(overview.model.nodes.length,16);assert.equal(overview.model.edges.length,18);assert.equal(detail.model.nodes.length,14);assert.equal(detail.model.edges.length,14);
 const chain=[['RES-CDN','BEHAVIOR-API'],['BEHAVIOR-API','ORIGIN-API'],['ORIGIN-API','RES-VPC-ORIGIN'],['RES-VPC-ORIGIN','RES-ALB'],['RES-ALB','RES-LISTENER'],['RES-LISTENER','RES-RULE-API'],['RES-RULE-API','RES-TG'],['RES-TG','RES-SERVICE-API'],['RES-SERVICE-API','RES-TASK-API']];
 for(const[a,b]of chain)assert.ok(detail.model.edges.some(e=>e.from.key===a&&e.to.key===b),a+' → '+b);
 const brokenFile=resource('RES-RULE-API'),beforeBroken=fs.readFileSync(path.join(ws,brokenFile),'utf8');
 try{
  const broken=read(brokenFile);broken.properties.Actions[0].TargetGroupArn.ref='RES-MISSING';save(brokenFile,broken);
  assert.ok(view('entry-flow').diagnostics.some(d=>d.code==='endpoint-unresolved'));
  assert.ok(view('architecture-flow').diagnostics.some(d=>d.code==='source-unresolved'));
 }finally{fs.writeFileSync(path.join(ws,brokenFile),beforeBroken);}
 const input=exported('deployment-input');assert.equal(input.length,211);
 for(const r of input){assert.deepEqual(r.properties,read(resource(r.designId)).properties);assert.equal(r.contractVersion,2);assert.ok(r.stack.region);}
 const apps=exported('application-input');assert.equal(apps.length,2);assert.deepEqual(apps.find(a=>a.id==='APP-API').settings,read('model/apps/api.yaml').settings);
 assert.equal(exported('foundation-input').length,4);assert.equal(exported('parameters-input').filter(p=>p.state==='unbound').length,33);
 const report=cli(['report',ws,'parameter-book','--print']);assert.ok(!report.includes('{#each'));assert.match(report,/HealthCheckIntervalSeconds: 30/);assert.match(report,/idleTtlSeconds: 1800/);assert.match(report,/Priority: 100/);assert.match(report,/unbound/);
 assert.equal((report.match(/^### (RES-|ALM-|BKP-)/gm)||[]).length,212); // 211資源 + Inspectorのサービス設定
 for(const file of fs.readdirSync(path.join(ws,'model/scenarios'))){const flow=json(['view',ws,'scenario-flow','model/scenarios/'+file]);assert.deepEqual(flow.errors,[]);assert.deepEqual(flow.diagnostics,[]);}
 console.log('正例 OK: 211資源・2構成図・全設定表・4生成入力・全設定書・6処理シナリオ');

 // 既存APIをそのままに、別の登録先を持つServiceへ転送する。表示用の接続を編集しない。
 const ruleFile=resource('RES-RULE-API'),originalRule=fs.readFileSync(path.join(ws,ruleFile),'utf8');
 const tempIds=['RES-TG-ALTERNATE','RES-SERVICE-ALTERNATE'];
 try{
  const tg=read(resource('RES-TG'));tg.id=tempIds[0];tg.logicalId='AlternateTargetGroup';tg.name='別のAPI登録先';save(resource(tg.id),tg);
  const svc=read(resource('RES-SERVICE-API'));svc.id=tempIds[1];svc.logicalId='AlternateService';svc.name='転送先を変更したAPI';svc.properties.LoadBalancers[0].TargetGroupArn.ref=tg.id;save(resource(svc.id),svc);
  const rule=read(ruleFile);rule.properties.Actions[0].TargetGroupArn.ref=tg.id;rule.properties.Priority=150;save(ruleFile,rule);
  clean(json(['workspace','validate',ws]));
  const changed=view('architecture-flow');assert.deepEqual(changed.diagnostics,[]);
  assert.ok(changed.model.edges.some(e=>e.from.key==='RES-ALB'&&e.to.key===svc.id&&e.label.includes('150')));
  assert.ok(!changed.model.edges.some(e=>e.from.key==='RES-ALB'&&e.to.key==='RES-SERVICE-API'));
  const detailChanged=view('entry-flow');assert.ok(detailChanged.model.edges.some(e=>e.from.key==='RES-RULE-API'&&e.to.key===tg.id));
  const table=view('aws-home').sections.find(s=>s.id==='rules').rows[0];assert.equal(table.cells.find(c=>c.label==='Priority').value,150);assert.equal(table.cells.find(c=>c.label==='Actions').value[0].TargetGroupArn.ref,tg.id);
  assert.equal(exported('deployment-input').find(r=>r.designId==='RES-RULE-API').properties.Actions[0].TargetGroupArn.ref,tg.id);
  const changedReport=cli(['report',ws,'parameter-book','--print']);assert.match(changedReport,/Priority: 150/);assert.ok(changedReport.includes(tg.id));
  console.log('追従 OK: Ruleの転送先 → 全体図・詳細図・設定表・生成入力・設計書');
 }finally{fs.writeFileSync(path.join(ws,ruleFile),originalRule);for(const id of tempIds)fs.unlinkSync(path.join(ws,resource(id)));}
 negative('登録ポートが不一致',resource('RES-SERVICE-API'),d=>d.properties.LoadBalancers[0].ContainerPort=9090,/登録ポート|登録コンテナ/);
 negative('希望台数が最大を超える',resource('RES-SERVICE-API'),d=>d.properties.DesiredCount=99,/スケール範囲/);
 negative('S3の公開を許可',resource('RES-S3-FILES'),d=>d.properties.PublicAccessBlockConfiguration.BlockPublicPolicy=false,/Block Public Access/);
 negative('セッションTTLが逆転','model/apps/api.yaml',d=>d.settings.session.idleTtlSeconds=30000,/無操作TTL/);
 negative('接続DBポートが不一致','model/apps/api.yaml',d=>d.settings.database.port=15432,/DB接続ポート/);
 negative('Subnetに別サービスを指定',resource('RES-ALB'),d=>d.properties.Subnets[0].ref='RES-S3-FILES',/Subnets.*EC2::Subnet/);
 negative('同一AZを二つ使用',resource('RES-SUB-APP-B'),d=>d.properties.AvailabilityZoneId.ref='INPUT-AZ-A',/異なるAZ/);
 negative('Logical IDの重複',resource('RES-SERVICE-WORKER'),d=>d.logicalId=read(resource('RES-SERVICE-API')).logicalId,/Logical ID/);
 negative('入力の束縛済み偽装','model/inputs.yaml',d=>d.inputs[0].state='bound',/実際の値/);
 negative('配列の誤入力',resource('RES-ALB'),d=>d.properties.Subnets='たくさん',/Subnets/,2);
 assert.deepEqual(snapshot(lab),originalHashes,'原本を変更していない');
 console.log(`受入検査成功: CLI ${calls}回。AWSへの配備・動作確認は含まない。`);
}catch(e){console.error('検査の記録: '+work);throw e;}
finally{if(!keep){const target=path.resolve(work);assert.equal(path.dirname(target),path.join(repo,'scratchpad'));assert.ok(path.basename(target).startsWith('aws-webapp-verify-'));fs.rmSync(target,{recursive:true,force:true});}}
