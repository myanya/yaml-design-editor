import { useEffect, useRef, useState } from 'react';
import { Alert, Box, Button, CircularProgress, Divider, Paper, Stack, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import * as api from '../api';
import { ActionFields } from './ActionFields';

export function BusinessOperations({ session, revision, entity, refresh }: {
  session: api.Session; revision: string; entity: string | null; refresh: () => Promise<void>;
}) {
  const [input, setInput] = useState<api.OperationInput>();
  const [inputRevision, setInputRevision] = useState('');
  const [values, setValues] = useState<Record<string, api.JsonValue>>({});
  const [preview, setPreview] = useState<api.OperationPreview>();
  const [result, setResult] = useState<api.OperationResult>();
  const [requestId, setRequestId] = useState('');
  const [lookup, setLookup] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  const stale = !!input && (revision !== inputRevision || now >= Date.parse(input.expiresAt));
  const expiredPreview = !!preview && now >= Date.parse(preview.expiresAt);
  useEffect(() => { if (stale && !sent) setPreview(undefined); }, [stale, sent]);
  const catalog = useQuery({ queryKey: ['business', session.workspaceId, session.clientId, revision, 'operations', entity],
    queryFn: ({ signal }) => api.operations(revision, entity, signal), enabled: !input && !busy,
    staleTime: Infinity, refetchOnWindowFocus: false,
    retry: (count, failure) => count < 2 && failure instanceof api.ConnectionError && failure.code === 'busy', retryDelay: 1000 });
  async function run(task: () => Promise<void>) {
    if (running.current) return;
    running.current = true; setBusy(true); setError(undefined);
    try { await task(); } catch (failure) { setError(failure instanceof Error ? failure.message : '操作に失敗しました'); }
    finally { running.current = false; setBusy(false); }
  }
  async function start(action: api.BusinessAction) {
    await run(async () => {
      const response = await api.prepareOperation(action, revision, session.csrfToken);
      setInput(response.data); setInputRevision(revision); setValues(response.data.values);
      setPreview(undefined); setResult(undefined); setRequestId(''); setSent(false);
    });
  }
  function change(next: Record<string, api.JsonValue>) { setValues(next); setPreview(undefined); setRequestId(''); }
  async function confirmed(value: api.OperationResult) { setResult(value); await refresh(); }
  const uncertain = sent && (!result || ['received', 'applying', 'unknown', 'partial'].includes(result.state));
  return <Paper component="section" variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
    <Typography component="h2" variant="h6" sx={{ mb: 2 }}>業務操作</Typography>
    {busy && <Stack direction="row" spacing={1} sx={{ mb: 2 }}><CircularProgress size={20} /><Typography>操作を処理しています…</Typography></Stack>}
    {error && <Alert severity="warning" sx={{ mb: 2 }}>{error}</Alert>}
    {input ? <Stack spacing={2}>
      <div><Typography component="h3" variant="h6">{input.label}</Typography><Typography color="text.secondary">対象: {input.target ?? 'ワークスペース全体'}</Typography></div>
      {stale && !sent && <Alert severity="warning">内容が変わったか入力の期限が切れました。この操作を閉じ、最新の内容から開き直してください。</Alert>}
      {input.changedOnly && <Typography variant="body2">変更した項目を送ります。項目の消去には、操作で用意された消去欄を使ってください。</Typography>}
      <Box component="form" onSubmit={(event) => { event.preventDefault(); if (!stale && !sent) void run(async () => {
        setPreview(undefined);
        const response = await api.planOperation(input.inputId, values, session.csrfToken);
        setPreview(response.data); setRequestId(crypto.randomUUID());
      }); }}>
        <ActionFields fields={input.fields} values={values} change={change} disabled={busy || sent || stale} />
        <Button sx={{ mt: 2 }} type="submit" variant="outlined" disabled={busy || sent || stale}>変更内容を確認</Button>
      </Box>
      {preview && !sent && !stale && <Stack spacing={2}>
        <Divider /><Typography component="h4" variant="subtitle1">保存する変更</Typography>
        {preview.issues.map((issue, index) => <Alert key={index} severity={issue.severity === 'error' ? 'error' : 'warning'}>{issue.document} {issue.path}: {issue.message}</Alert>)}
        {preview.failedDocuments.map((document) => <Alert key={document} severity="error">{document}: 検証できませんでした。デスクトップで確認してください。</Alert>)}
        {preview.documents.map((document) => <Box component="details" key={document.document} open>
          <Typography component="summary" sx={{ cursor: 'pointer' }}>{document.document}</Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2, mt: 1 }}>
            <ChangeText label="変更前" text={document.before ?? '（新規文書）'} /><ChangeText label="変更後" text={document.after} />
          </Box>
        </Box>)}
        {!preview.documents.length && <Alert severity="info">保存する変更はありません。</Alert>}
        {!preview.canCommit && <Alert severity="error">検証エラーが残るため保存できません。入力やデスクトップの文書を確認してください。</Alert>}
        {expiredPreview && <Alert severity="warning">確認の期限が切れました。変更内容を確認し直してください。</Alert>}
        <Typography variant="body2">適用すると保存されます。外部からの変更はデスクトップの Undo では取り消せません。</Typography>
        <Button variant="contained" disabled={busy || stale || expiredPreview || !preview.canCommit || !preview.documents.length}
          onClick={() => { if (!sent) void run(async () => { setSent(true); await confirmed((await api.commitOperation(preview.planId, requestId, session.csrfToken)).data); }); }}>適用して保存</Button>
      </Stack>}
      {requestId && <Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>要求 ID: {requestId}</Typography>}
      {uncertain && <Alert severity="warning">保存結果を確認してください。同じ操作をやり直さず、結果を照会してください。部分保存・結果不明の場合はデスクトップで確認が必要です。</Alert>}
      {sent && <Button disabled={busy} onClick={() => void run(async () => confirmed((await api.operationResult(requestId)).data))}>結果を照会</Button>}
      {result && <OperationResult value={result} />}
      <Button disabled={busy || uncertain} onClick={() => { setInput(undefined); setPreview(undefined); setError(undefined); void refresh(); }}>操作を閉じる</Button>
    </Stack> : <Stack spacing={1}>
      {catalog.isPending ? <Typography color="text.secondary">利用できる操作を確認しています…</Typography> : catalog.error ?
        <Alert severity="warning" action={<Button onClick={() => void catalog.refetch()}>再取得</Button>}>{catalog.error.message}</Alert> :
        !catalog.data?.length ? <Typography color="text.secondary">この場所で許可されている操作はありません。</Typography> : catalog.data.map((action) => <Box key={`${action.name}/${action.target}`}>
          <Button variant="outlined" disabled={busy || !action.enabled} onClick={() => void start(action)}>{action.label}</Button>
          {action.target && <Typography component="span" variant="caption" sx={{ ml: 1 }}>{action.target}</Typography>}
          {(action.reason || action.description) && <Typography variant="body2" color="text.secondary">{action.reason ?? action.description}</Typography>}
        </Box>)}
    </Stack>}
    {!input && <Box component="details" sx={{ mt: 2 }}><Typography component="summary" sx={{ cursor: 'pointer' }}>要求 ID から保存結果を確認</Typography>
      <Stack direction="row" sx={{ mt: 2, gap: 1 }}><TextField size="small" label="要求 ID" value={lookup} onChange={(event) => { setLookup(event.target.value); setResult(undefined); }} />
        <Button disabled={busy || !/^[A-Za-z0-9_-]{1,180}$/.test(lookup)} onClick={() => void run(async () => confirmed((await api.operationResult(lookup)).data))}>結果を照会</Button></Stack>
      {result && <OperationResult value={result} />}
    </Box>}
  </Paper>;
}

function ChangeText({ label, text }: { label: string; text: string }) {
  return <Box sx={{ minWidth: 0 }}><Typography variant="caption">{label}</Typography><Box component="pre" sx={{ p: 1.5, bgcolor: '#f5f7f8', overflow: 'auto', maxHeight: 320, fontSize: 12 }}>{text}</Box></Box>;
}
function OperationResult({ value }: { value: api.OperationResult }) {
  const labels: Record<api.OperationResult['state'], string> = { received: '要求を受け付けました。結果を再照会してください。', applying: '保存中です。結果を再照会してください。',
    committed: '保存しました。', rejected: '保存は拒否されました。最新の状態で操作を開き直してください。', partial: '一部だけ保存されました。デスクトップで確認してください。',
    unknown: '保存結果を確定できません。デスクトップで照合してください。', reconciled: 'デスクトップで現在の内容を照合済みです。' };
  return <Alert severity={value.state === 'committed' || value.state === 'reconciled' ? 'success' : 'warning'}>{labels[value.state]}
    {Object.entries(value.persistence).map(([document, state]) => <Typography key={document} variant="body2">{document}: {({ WRITTEN: '保存済み', UNCHANGED: '変更なし', NOT_WRITTEN: '未保存', UNKNOWN: '結果不明' } as Record<string, string>)[state] ?? 'デスクトップで確認してください'}</Typography>)}
  </Alert>;
}
