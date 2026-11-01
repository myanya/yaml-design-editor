import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import type { Reference, Entity } from '../api';

export function RawValue({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <Typography component="span" color="text.secondary">—</Typography>;
  if (Array.isArray(value)) return value.length ? <Stack spacing={1}>{value.map((item, i) => <Box key={i} sx={{ pl: 1, borderLeft: '2px solid #dce3e8' }}><RawValue value={item} /></Box>)}</Stack> : <span>なし</span>;
  if (typeof value === 'object') return <Box component="dl" sx={{ m: 0 }}>{Object.entries(value).map(([key, item]) => <Box key={key} sx={{ mb: 1 }}>
    <Typography component="dt" variant="caption" color="text.secondary">{key}</Typography><Box component="dd" sx={{ m: 0 }}><RawValue value={item} /></Box>
  </Box>)}</Box>;
  return <Typography component="span" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{String(value)}</Typography>;
}

export function EntityLink({ id, title, open }: { id: string; title?: string | null; open: (id: string) => void }) {
  return <Button size="small" sx={{ textAlign: 'left', justifyContent: 'flex-start', overflowWrap: 'anywhere' }} onClick={() => open(id)}>{title ? `${title}（${id}）` : id}</Button>;
}

export function Value({ value, display, references, open }: { value: unknown; display: string | null; references: Reference[]; open: (id: string) => void }) {
  return <Stack spacing={0.5}>
    {display !== null ? <Typography component="span" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{display}</Typography> : <RawValue value={value} />}
    {references.map((ref, i) => ref.kind === 'entity' && ref.resolved ? <EntityLink key={i} id={ref.id} title={ref.title} open={open} /> :
      <Chip key={i} size="small" variant="outlined" color={ref.kind === 'entity' ? 'warning' : 'default'} label={`${ref.id}（${ref.kind === 'entity' ? '参照先が見つかりません' : '文書参照'}）`} sx={{ maxWidth: '100%', height: 'auto', '& .MuiChip-label': { whiteSpace: 'normal' } }} />)}
  </Stack>;
}

export function EntityDetails({ entity, open }: { entity: Entity; open: (id: string) => void }) {
  return <Stack spacing={3}>
    <div><Typography component="h2" variant="h4">{entity.title || entity.id}</Typography><Typography color="text.secondary">{entity.id}</Typography></div>
    {entity.sections.map((section, i) => <Box component="section" key={i}>
      <Typography component="h3" variant="h6" sx={{ mb: 2 }}>{section.label}</Typography>
      <Box component="dl" className="business-fields">{section.fields.map((field, j) => <Box key={j} className="business-field">
        <Typography component="dt" color="text.secondary">{field.label}</Typography>
        <Box component="dd" sx={{ m: 0, minWidth: 0 }}><Value {...field} open={open} />{field.duplicateOf && <Typography variant="caption" color="text.secondary">別の区分にも同じ項目があります。</Typography>}</Box>
      </Box>)}</Box>
    </Box>)}
    {entity.unassigned.length > 0 && <Typography color="text.secondary">業務項目に割り当てられていない出現があります。詳しくはデスクトップで確認できます。</Typography>}
    {entity.backlinks.length > 0 && <Box><Typography component="h3" variant="h6">この対象を参照しているもの</Typography>
      <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>{entity.backlinks.map((link, i) => <EntityLink key={i} {...link} open={open} />)}</Stack></Box>}
  </Stack>;
}
