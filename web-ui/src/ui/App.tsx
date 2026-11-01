import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Box, Button, Chip, CircularProgress, Container, Divider, Paper, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Route, Routes, useNavigate } from 'react-router-dom';
import * as api from '../api';
import { BusinessWorkspace } from './BusinessWorkspace';

export function App() {
  const client = useQueryClient();
  const connection = useQuery({ queryKey: ['session'], queryFn: ({ signal }) => api.session(signal), refetchInterval: 5_000 });
  const navigate = useNavigate();
  const disconnected = useCallback(() => { client.clear(); navigate('/connect', { replace: true }); }, [client, navigate]);
  useEffect(() => {
    if (connection.isError) {
      client.removeQueries({ queryKey: ['workspace'] });
      client.removeQueries({ queryKey: ['business'] });
    }
  }, [connection.isError, client]);
  const active = connection.data && !connection.isError;
  return <>
    <Box component="header" className="app-header">
      <Container maxWidth="lg"><Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', gap: 2 }}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}><Box className="brand-mark">Y</Box><div>
          <Typography sx={{ fontWeight: 700 }}>YAML Design Editor</Typography><Typography variant="caption" color="text.secondary">業務 Web</Typography>
        </div></Stack>
        <Chip size="small" label={active ? '接続中' : 'この PC 内で接続'} color={active ? 'success' : 'default'} variant="outlined" />
      </Stack></Container>
    </Box>
    <Container component="main" maxWidth="lg" className="main-content">
      {connection.isPending ? <Stack spacing={2} sx={{ alignItems: 'center', py: 10 }}><CircularProgress size={30} /><Typography>接続を確認しています…</Typography></Stack> :
        <Routes><Route path="*" element={active ? <WorkspaceHome session={connection.data!} disconnected={disconnected} /> :
          <Connect error={connection.error} connected={(session) => { client.clear(); client.setQueryData(['session'], session); navigate('/', { replace: true }); }} />} /></Routes>}
    </Container>
    <Container component="footer" maxWidth="lg" className="app-footer"><Typography variant="caption" color="text.secondary">保存済みの情報を、デスクトップと同じワークスペースから。</Typography></Container>
  </>;
}

function Connect({ error, connected }: { error: Error | null; connected: (session: api.Session) => void }) {
  const [code, setCode] = useState('');
  const login = useMutation({ mutationFn: api.login, onSuccess: connected, onSettled: () => setCode('') });
  const unavailable = error instanceof api.ConnectionError && error.status !== 401;
  function submit(event: FormEvent) { event.preventDefault(); if (code.trim()) login.mutate(code.trim()); }
  return <Box className="connect-layout">
    <Stack spacing={3} className="connect-intro">
      <Typography variant="overline" color="primary" sx={{ fontWeight: 700 }}>WORKSPACE ACCESS</Typography>
      <Typography component="h1" variant="h3" sx={{ fontWeight: 700, fontSize: { xs: 30, md: 42 }, lineHeight: 1.4 }}>ワークスペースに<br />接続する</Typography>
      <Typography color="text.secondary" sx={{ lineHeight: 1.9 }}>YDE で開いているワークスペースを、このブラウザから利用します。接続する人と閲覧範囲は、デスクトップ側で設定できます。</Typography>
      <Divider /><Stack spacing={1.5}>
        <Typography variant="body2">1. YDE の「設定 → 接続と常駐」で Web を ON</Typography>
        <Typography variant="body2">2. Web 用の接続者を登録し、閲覧権限を選ぶ</Typography>
        <Typography variant="body2">3. 「接続コードを発行」でコードをコピー</Typography>
      </Stack>
    </Stack>
    <Paper component="section" variant="outlined" className="connect-card">
      <Typography component="h2" variant="h5" sx={{ fontWeight: 700, mb: 1 }}>接続コードを入力</Typography>
      <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>デスクトップで発行したコードを貼り付けてください。</Typography>
      {unavailable && <Alert severity="warning" sx={{ mb: 2 }}>{error.message}</Alert>}
      {login.error && <Alert severity="error" sx={{ mb: 2 }}>{login.error.message}</Alert>}
      <Box component="form" onSubmit={submit}>
        <Stack spacing={2.5}>
          <TextField label="接続コード" type="password" value={code} onChange={(event) => setCode(event.target.value)} fullWidth
            autoComplete="one-time-code" disabled={login.isPending} slotProps={{ htmlInput: { maxLength: 100, spellCheck: false } }}
            helperText="有効期限は発行から 2 分、使用は 1 回です。" />
          <Button type="submit" variant="contained" size="large" disabled={!code.trim() || login.isPending}>{login.isPending ? '接続しています…' : '接続する'}</Button>
        </Stack>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>コードが期限切れの場合は、デスクトップから再発行できます。</Typography>
    </Paper>
  </Box>;
}

function WorkspaceHome({ session, disconnected }: { session: api.Session; disconnected: () => void }) {
  const overview = useQuery({ queryKey: ['workspace', session.clientId, session.workspaceId], queryFn: ({ signal }) => api.workspace(signal), refetchInterval: 5_000,
    retry: (failures, error) => failures < 2 && error instanceof api.ConnectionError && error.code === 'busy', retryDelay: 1_000 });
  const refresh = useCallback(async () => { await overview.refetch(); }, [overview.refetch]);
  const exit = useMutation({ mutationFn: () => api.logout(session.csrfToken), onSuccess: disconnected,
    onError: (error) => { if (error instanceof api.ConnectionError && error.status === 401) disconnected(); } });
  useEffect(() => { if (overview.error instanceof api.ConnectionError && overview.error.status === 401) disconnected(); }, [overview.error, disconnected]);
  return <Stack spacing={4}>
    <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' }, gap: 2 }}>
      <div><Typography variant="overline" color="primary" sx={{ fontWeight: 700 }}>WORKSPACE</Typography><Typography component="h1" variant="h4" sx={{ fontWeight: 700 }}>ワークスペース概要</Typography></div>
      <Button variant="outlined" onClick={() => exit.mutate()} disabled={exit.isPending}>ログアウト</Button>
    </Stack>
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        {exit.error && <Alert severity="error">ログアウトを確認できませんでした。{exit.error.message}</Alert>}
        <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}><Chip label="接続済み" color="success" size="small" /><Typography sx={{ fontWeight: 600 }}>{session.clientName}</Typography></Stack>
        {overview.isPending ? <Typography>概要を読み込んでいます…</Typography> : overview.isError ?
          <Alert severity="warning" action={<Button onClick={refresh}>再取得</Button>}>{overview.error.message}</Alert> : <Stack direction="row" spacing={1}><Typography color="text.secondary">閲覧できる文書</Typography><Typography sx={{ fontWeight: 700 }}>{overview.data.data.documents}件</Typography></Stack>}
        <Typography variant="caption" color="text.secondary">接続の期限: {new Date(session.expiresAt).toLocaleString('ja-JP')}</Typography>
      </Stack>
    </Paper>
    {overview.isSuccess && <BusinessWorkspace session={session} revision={overview.data.revision} refresh={refresh} />}
  </Stack>;
}
