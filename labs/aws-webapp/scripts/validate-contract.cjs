// 原本の設定を、取得済みのAWS公式Resource Specificationと照合する。
// サービス側の全制約・IAMの実効権限・提供リージョン・配備可否を判定するものではない。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
function repository(start){let dir=start;while(!fs.existsSync(path.join(dir,'gradlew'))){const p=path.dirname(dir);if(p===dir)throw Error('リポジトリを見つけられません');dir=p;}return dir;}
const repo=repository(__dirname),yaml=require(path.join(repo,'web-ui/node_modules/js-yaml'));
function validate(lab){
 const read=f=>yaml.load(fs.readFileSync(path.join(lab,f),'utf8'));
 const resources=fs.readdirSync(path.join(lab,'model/resources')).filter(f=>f.endsWith('.yaml')).map(f=>read('model/resources/'+f));
 const spec=JSON.parse(fs.readFileSync(path.join(lab,'reference/cloudformation-spec.json'),'utf8'));
 const byId=new Map(resources.map(r=>[r.id,r]));const errors=[],bindings=[];
 const inputs=new Set(read('model/inputs.yaml').inputs.map(i=>i.id));
 const apps=new Set(fs.readdirSync(path.join(lab,'model/apps')).map(f=>read('model/apps/'+f).id));
 const nestedIds=new Set();
 for(const r of resources)if(r.resourceType==='AWS::CloudFront::Distribution')for(const o of r.properties.DistributionConfig.Origins)nestedIds.add(o.Id);
 function binding(v,where){
  if(v&&typeof v==='object'&&!Array.isArray(v)&&('ref' in v||'literal' in v)){
   if('ref'in v&&'literal'in v)errors.push(where+': refとliteralの混在');
   if('ref'in v){bindings.push({id:v.ref,attribute:v.attribute||'Ref',where});return true;}
   return false;
  }return false;
 }
 function scan(v,where){if(v&&typeof v==='object'){if(binding(v,where))return;for(const[k,x]of Object.entries(v))scan(x,where+'.'+k);}}
 function properties(v,defs,type,where,shape){
  for(const[k,d]of Object.entries(defs))if(d.Required&&!(k in v))errors.push(where+'.'+k+': AWSの必須項目がありません');
  for(const[k,x]of Object.entries(v)){
   if(k==='id'&&['CacheBehavior','DefaultCacheBehavior'].includes(shape))continue;
   if(!defs[k])errors.push(where+'.'+k+': AWSにないプロパティ');else check(x,defs[k],type,where+'.'+k);
  }
 }
 function check(v,d,type,where){
  if(binding(v,where))return;
  if(v&&typeof v==='object'&&'literal'in v)v=v.literal;
  if(d.PrimitiveType){
   const t=d.PrimitiveType;
   if(t==='Json'){scan(v,where);return;}
   const expected=['Integer','Long','Double'].includes(t)?'number':t==='Boolean'?'boolean':'string';
   if(typeof v!==expected||(['Integer','Long'].includes(t)&&!Number.isInteger(v)))errors.push(where+': AWS型 '+t+' と不一致');
   return;
  }
  if(d.Type==='List'){
   if(!Array.isArray(v)){errors.push(where+': 配列ではありません');return;}
   v.forEach((x,i)=>check(x,d.PrimitiveItemType?{PrimitiveType:d.PrimitiveItemType}:{Type:d.ItemType},type,where+'['+i+']'));return;
  }
  if(d.Type==='Map'){
   if(!v||typeof v!=='object'||Array.isArray(v)){errors.push(where+': mapではありません');return;}
   for(const[k,x]of Object.entries(v))check(x,d.PrimitiveItemType?{PrimitiveType:d.PrimitiveItemType}:{Type:d.ItemType},type,where+'.'+k);return;
  }
  const s=d.Type==='Tag'?spec.PropertyTypes.Tag:spec.PropertyTypes[type+'.'+d.Type]||spec.PropertyTypes[d.Type];
  if(!s){errors.push(where+': 未知のAWS型 '+d.Type);return;}
  if(!v||typeof v!=='object'||Array.isArray(v)){errors.push(where+': objectではありません');return;}
  properties(v,s.Properties,type,where,d.Type);
 }
 for(const r of resources){
  const d=spec.ResourceTypes[r.resourceType];
  if(!d)errors.push(r.id+': AWS型が検証用仕様にありません');else properties(r.properties,d.Properties,r.resourceType,r.id+'.properties');
 }
 const adapters={CloudFrontArn:'AWS::CloudFront::Distribution',SesIdentityArn:'AWS::SES::EmailIdentity',EcsServiceResourceId:'AWS::ECS::Service',S3Uri:'AWS::S3::Bucket','SecretString:password':'AWS::SecretsManager::Secret',ConfiguredName:'AWS::Logs::DeliverySource'};
 for(const b of bindings){
  const target=byId.get(b.id);
  if(b.attribute==='Parameter'){if(!inputs.has(b.id))errors.push(b.where+': 配備入力ではありません');continue;}
  if(b.attribute==='ApplicationSettings'){if(!apps.has(b.id))errors.push(b.where+': アプリ設定ではありません');continue;}
  if(b.attribute==='Literal'){if(!nestedIds.has(b.id))errors.push(b.where+': Origin Idではありません');continue;}
  if(!target){errors.push(b.where+': AWS資源がありません '+b.id);continue;}
  if(adapters[b.attribute]){if(target.resourceType!==adapters[b.attribute])errors.push(b.where+': 展開属性の対象型が不一致');continue;}
  if(b.attribute!=='Ref'&&!spec.ResourceTypes[target.resourceType]?.Attributes?.[b.attribute])errors.push(b.where+': AWSの属性がありません '+b.attribute);
 }
 return {resources:resources.length,types:new Set(resources.map(r=>r.resourceType)).size,bindings:bindings.length,specification:spec.ResourceSpecificationVersion,errors};
}
if(require.main===module){const result=validate(path.resolve(process.argv[2]||path.join(__dirname,'..')));console.log(JSON.stringify(result,null,2));process.exitCode=result.errors.length?1:0;}
module.exports={validate,repository};
