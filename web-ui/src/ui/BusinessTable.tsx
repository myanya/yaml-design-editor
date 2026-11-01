import { useState } from 'react';
import { Box, Button, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import type { Row } from '../api';
import { EntityLink, Value } from './BusinessValues';

/** 行の意味と並びはサーバの結果。ここでは表示件数だけを分ける。 */
export function BusinessTable({ columns, rows, open }: { columns: string[]; rows: Row[]; open: (id: string) => void }) {
  const [page, setPage] = useState(0);
  const start = Math.min(page * 25, Math.max(0, Math.ceil(rows.length / 25) - 1) * 25);
  if (!rows.length) return <Typography color="text.secondary" sx={{ py: 3 }}>該当する対象はありません。</Typography>;
  return <Box>
    <TableContainer><Table size="small" aria-label="業務一覧">
      <TableHead><TableRow><TableCell>対象</TableCell>{columns.map((column, i) => <TableCell key={i}>{column}</TableCell>)}</TableRow></TableHead>
      <TableBody>{rows.slice(start, start + 25).map((row) => <TableRow key={row.key} hover>
        <TableCell sx={{ minWidth: 150 }}>{row.origins.length ? <EntityLink id={row.id} title={row.title} open={open} /> : row.title || row.id}</TableCell>
        {row.cells.map((cell, i) => <TableCell key={i} sx={{ minWidth: 100, maxWidth: 360, verticalAlign: 'top' }}><Value {...cell} open={open} />
          {cell.backlinks?.map((back, j) => back.id && <EntityLink key={j} id={back.id} open={open} />)}
        </TableCell>)}
      </TableRow>)}</TableBody>
    </Table></TableContainer>
    {rows.length > 25 && <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mt: 2 }}>
      <Button disabled={start === 0} onClick={() => setPage(Math.max(0, start / 25 - 1))}>前の行</Button>
      <Typography variant="body2">{start + 1}〜{Math.min(start + 25, rows.length)} / {rows.length} 行</Typography>
      <Button disabled={start + 25 >= rows.length} onClick={() => setPage(start / 25 + 1)}>次の行</Button>
    </Stack>}
  </Box>;
}
