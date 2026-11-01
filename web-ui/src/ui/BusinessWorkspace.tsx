import { lazy, Suspense, useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, CircularProgress, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import * as api from '../api';
import { BusinessTable } from './BusinessTable';
import { EntityDetails, EntityLink } from './BusinessValues';
import { BusinessOperations } from './BusinessOperations';

const BusinessChart = lazy(() => import('./BusinessChart'));
type Selection = { date?: string; inputs: Record<string, string | null>; section?: string; offset: number };
const initial: Selection = { inputs: {}, offset: 0 };
// 保存版を鍵にした結果は再利用する。戻るたびの再評価を避け、混雑だけ短く再試行する。
const readOptions = { staleTime: Infinity, refetchOnWindowFocus: false,
  retry: (failures: number, error: Error) => failures < 2 && error instanceof api.ConnectionError && error.code === 'busy',
  retryDelay: (attempt: number) => (attempt + 1) * 1_000 };

export function BusinessWorkspace({ session, revision, refresh }: { session: api.Session; revision: string; refresh: () => Promise<void> }) {
  const client = useQueryClient();
  const reload = async () => { await refresh(); await client.invalidateQueries({ queryKey: ['business', session.workspaceId, session.clientId] }); };
  const [location, setLocation] = useSearchParams();
  const [memories, setMemories] = useState<Record<string, Selection>>({});
  const scope = [session.workspaceId, session.clientId, revision];
  const catalog = useQuery({ ...readOptions, queryKey: ['business', ...scope, 'views'], queryFn: ({ signal }) => api.views(revision, signal) });
  const available = catalog.data?.filter((view) => view.supported) ?? [];
  const unsupported = catalog.data?.filter((view) => !view.supported) ?? [];
  const defaults = available.filter((view) => view.kind === 'dashboard' && view.default);
  const defaultId = (defaults.length === 1 ? defaults[0] : available.find((view) => view.kind === 'dashboard') ?? available[0])?.id;
  const viewId = location.get('view') ?? defaultId;
  const selectedDefinition = catalog.data?.find((view) => view.id === viewId);
  const entityId = location.get('entity');
  const memory = viewId ? memories[viewId] ?? initial : initial;
  const setMemory = (change: Partial<Selection>) => { if (viewId) setMemories((old) => ({ ...old, [viewId]: { ...(old[viewId] ?? initial), ...change } })); };
  const selected = useQuery({ ...readOptions, queryKey: ['business', ...scope, 'view', viewId, memory.date, memory.inputs, memory.offset],
    enabled: !!selectedDefinition?.supported && !entityId && catalog.isSuccess,
    refetchInterval: !memory.date && available.find((view) => view.id === viewId)?.kind === 'dashboard' ? 60_000 : false,
    queryFn: ({ signal }) => api.view({ id: viewId!, revision, date: memory.date, inputs: memory.inputs, offset: memory.offset }, session.csrfToken, signal) });
  const detail = useQuery({ ...readOptions, queryKey: ['business', ...scope, 'entity', entityId], enabled: !!entityId,
    queryFn: ({ signal }) => api.entity(entityId!, revision, signal) });
  const open = (id: string) => { const next = new URLSearchParams(location); if (viewId) next.set('view', viewId); next.set('entity', id); setLocation(next); };
  const back = () => { const next = new URLSearchParams(location); next.delete('entity'); setLocation(next); };
  // 版の不一致は古い本文を表示せず、所有側から新しい保存版を取り直す。
  useEffect(() => {
    const stale = [catalog.error, selected.error, detail.error].some((error) => error instanceof api.ConnectionError && error.code === 'stale_revision');
    if (stale) void refresh();
  }, [catalog.error, selected.error, detail.error, refresh]);
  const error = catalog.error ?? (entityId ? detail.error : selected.error);
  return <Stack spacing={3}>
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: { sm: 'center' } }}>
      <TextField select size="small" label="業務ビュー" value={selectedDefinition ? viewId : ''} sx={{ minWidth: 240 }} disabled={!available.length}
        onChange={(event) => setLocation({ view: event.target.value })}>{catalog.data?.map((view) => <MenuItem key={view.id} value={view.id} disabled={!view.supported}>{view.name}{!view.supported && '（デスクトップで表示）'}</MenuItem>)}</TextField>
      <Chip label="保存済みデータ" size="small" variant="outlined" />
      <Button onClick={reload}>最新の保存内容を取得</Button>
    </Stack>
    {catalog.isSuccess && unsupported.length > 0 && <Alert severity="info">
      <Typography>デスクトップで表示するビュー</Typography>
      {unsupported.map((view) => <Typography key={view.id} variant="body2">{view.name}: {view.reason ?? 'この種類のビューは Web では表示できません。デスクトップで開いてください。'}</Typography>)}
    </Alert>}
    {error ? <Alert severity="warning" action={<Button onClick={reload}>再取得</Button>}>{error.message}</Alert> :
      catalog.isPending ? <Loading /> : entityId ? <Paper variant="outlined" sx={{ p: { xs: 2, md: 4 } }}>
        <Button onClick={back} sx={{ mb: 2 }}>一覧へ戻る</Button>
        {detail.isPending ? <Loading /> : detail.data && <EntityDetails entity={detail.data.data} open={open} />}
      </Paper> : !viewId ? <Alert severity="info">{unsupported.length ? 'このワークスペースのビューはデスクトップで開いてください。' : '利用できる業務ビューがありません。デスクトップでダッシュボードや表の定義を追加してください。'}</Alert> :
        !selectedDefinition?.supported ? <Alert severity="info">選択したビューは Web で表示できません。別のビューを選ぶか、デスクトップで開いてください。</Alert> :
        selected.isPending ? <Loading /> : selected.data && <ViewContent key={viewId} view={selected.data.data} memory={memory} change={setMemory} open={open} />}
    <BusinessOperations session={session} revision={revision} entity={entityId} refresh={reload} />
    <Typography variant="caption" color="text.secondary">未保存の編集は含みません。文書の編集はデスクトップで行います。</Typography>
  </Stack>;
}

function Loading() { return <Stack direction="row" spacing={2} sx={{ alignItems: 'center', py: 4 }}><CircularProgress size={22} /><Typography>保存済みの情報を読み込んでいます…</Typography></Stack>; }

function ViewContent({ view, memory, change, open }: { view: api.View; memory: Selection; change: (value: Partial<Selection>) => void; open: (id: string) => void }) {
  return <Stack spacing={3}>
    <div><Typography component="h2" variant="h4" sx={{ fontWeight: 700 }}>{view.name}</Typography>{view.description && <Typography color="text.secondary" sx={{ mt: 1 }}>{view.description}</Typography>}</div>
    {view.kind === 'dashboard' ? <DashboardContent report={view.report} memory={memory} change={change} open={open} /> : view.kind === 'chart' ?
      <Paper variant="outlined" sx={{ p: 3 }}><Suspense fallback={<Loading />}><BusinessChart data={view.report} /></Suspense></Paper> :
      <Paper variant="outlined" sx={{ p: 2 }}>
        {(view.report.definitionLimit !== null || view.report.truncated) && <Alert severity="info">表示範囲に上限があります。対象全体は {view.report.total} 行です。</Alert>}
        <BusinessTable key={memory.offset} columns={view.report.columns} rows={view.report.rows} open={open} />
        <Stack direction="row" spacing={2} sx={{ mt: 2 }}><Button disabled={!memory.offset} onClick={() => change({ offset: Math.max(0, memory.offset - 50) })}>前のページ</Button>
          <Typography sx={{ alignSelf: 'center' }}>{view.report.rows.length ? `${memory.offset + 1} 行目から / ` : ''}全 {view.report.total} 行</Typography>
          <Button disabled={view.report.nextOffset === null} onClick={() => change({ offset: view.report.nextOffset! })}>次のページ</Button></Stack>
      </Paper>}
  </Stack>;
}

function DashboardContent({ report, memory, change, open }: { report: api.Dashboard; memory: Selection; change: (value: Partial<Selection>) => void; open: (id: string) => void }) {
  const [date, setDate] = useState<string>();
  const [inputs, setInputs] = useState<Record<string, string | null>>({});
  const section = report.sections.find((section) => section.id === memory.section) ?? report.sections[0];
  const groups = [...new Set(report.sections.map((section) => section.group))].sort((a, b) => a === null ? -1 : b === null ? 1 : 0);
  // サーバが決めた既定値を表示する。未入力・空入力の意味をブラウザで再実装しない。
  return <>
    <Paper variant="outlined" component="form" sx={{ p: 2 }} onSubmit={(event) => { event.preventDefault(); change({ date: date ?? memory.date, inputs: { ...memory.inputs, ...inputs } }); }}>
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'center' }}>
        <TextField label="基準日" size="small" type="date" value={date ?? report.date} onChange={(event) => setDate(event.target.value)} required
          helperText={date || memory.date ? '指定した日' : '本日（自動更新）'} slotProps={{ inputLabel: { shrink: true } }} />
        {report.inputs.map((input) => <TextField key={input.key} label={input.label} size="small" select={input.type === 'enum'} type={input.type === 'date' ? 'date' : 'text'}
          value={inputs[input.key] ?? (Object.hasOwn(inputs, input.key) ? '' : input.value ?? '')} onChange={(event) => setInputs({ ...inputs, [input.key]: event.target.value })}
          sx={{ minWidth: 160 }} slotProps={{ inputLabel: { shrink: true }, htmlInput: { maxLength: 500 }, select: { displayEmpty: true } }}>
          {input.type === 'enum' && [<MenuItem key="" value="">絞り込まない</MenuItem>, ...input.options.map((option) => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>)]}
        </TextField>)}
        <Button type="submit" variant="contained">条件を反映</Button><Button onClick={() => { setDate(undefined); setInputs({}); change({ date: undefined, inputs: {} }); }}>条件をリセット</Button>
      </Stack>
    </Paper>
    {report.errors.map((error, i) => <Alert key={i} severity="warning">{error}</Alert>)}
    <Box className={report.placement === 'vertical' ? 'dashboard-vertical' : ''}>
      <Stack spacing={2} className="dashboard-sections">{groups.map((group) => <Box key={group ?? ''}>
        {group && <Typography variant="subtitle2" sx={{ mb: 1 }}>{group}</Typography>}
        <Box className="dashboard-tiles">{report.sections.filter((item) => item.group === group).map((item) => <Button key={item.id} variant={item.id === section?.id ? 'contained' : 'outlined'}
          onClick={() => change({ section: item.id })} sx={{ flexDirection: 'column', alignItems: 'flex-start', px: 2, py: 1.5, minWidth: 135 }}>
          <Typography component="span" variant="body2">{item.label}</Typography><Typography component="span" variant="h5">{item.count === null ? '確認が必要' : `${item.count} ${item.chart ? '項目' : '件'}`}</Typography>
          {item.pending.length > 0 && <Typography component="span" variant="caption">判定保留 {item.pending.length} 件</Typography>}
        </Button>)}</Box>
      </Box>)}</Stack>
      {section && <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, mt: 3, minWidth: 0 }}>
        <Typography component="h3" variant="h6" sx={{ mb: 2 }}>{section.label}</Typography>
        {section.error ? <Alert severity="warning">{section.error}</Alert> : <>
          {section.truncated && <Alert severity="info" sx={{ mb: 2 }}>定義の上限 {section.truncated.limit} 行までを表示しています（全 {section.truncated.total} 行）。</Alert>}
          {section.chart ? <Suspense fallback={<Loading />}><BusinessChart data={section.chart} /></Suspense> : <BusinessTable key={section.id} columns={section.columns} rows={section.rows} open={open} />}
          {section.pending.length > 0 && <Box sx={{ mt: 3 }}><Typography component="h4" variant="subtitle1">判定できない対象</Typography>
            {section.pending.map((pending, i) => <Stack key={i} direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}><EntityLink id={pending.id} open={open} /><Typography variant="body2">{pending.reason}</Typography></Stack>)}
          </Box>}
        </>}
      </Paper>}
    </Box>
  </>;
}
