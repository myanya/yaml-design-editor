import { useEffect, useRef } from 'react';
import { Alert, Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import { init, use } from 'echarts/core';
import type { ComposeOption } from 'echarts/core';
import { BarChart, LineChart, PieChart, ScatterChart } from 'echarts/charts';
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption, ScatterSeriesOption } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent, AriaComponent } from 'echarts/components';
import type { GridComponentOption, LegendComponentOption, TooltipComponentOption, AriaComponentOption } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import type { Chart } from '../api';

// 図のライブラリはこの部品だけに閉じる。集計・並替えはサーバの結果をそのまま使う。
use([BarChart, LineChart, PieChart, ScatterChart, GridComponent, LegendComponent, TooltipComponent, AriaComponent, SVGRenderer]);
type Option = ComposeOption<BarSeriesOption | LineSeriesOption | PieSeriesOption | ScatterSeriesOption | GridComponentOption | LegendComponentOption | TooltipComponentOption | AriaComponentOption>;

export default function BusinessChart({ data }: { data: Chart }) {
  const element = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!element.current || !data.categories.length) return;
    const chart = init(element.current, undefined, { renderer: 'svg' });
    const horizontal = data.chart === 'horizontal-bar' || data.chart === 'stacked-horizontal-bar';
    const stacked = ['stacked-bar', 'stacked-horizontal-bar', 'stacked-area'].includes(data.chart);
    const line = ['line', 'area', 'stacked-area'].includes(data.chart);
    const categories = data.categories.map((category) => category.label);
    const series: Option['series'] = data.chart === 'pie' ? [{ type: 'pie', radius: ['0%', '62%'], center: ['50%', '55%'],
      data: data.categories.flatMap((category, i) => { const value = data.series[0]?.values[i]; return value == null ? [] : [{ name: category.label, value }]; }) }] :
      data.chart === 'scatter' ? data.series.map((series) => ({ type: 'scatter', name: series.label,
        data: data.categories.map((category, i) => [category.x ?? null, series.values[i]]), symbolSize: 10 })) :
      data.series.map((series) => ({ type: line ? 'line' : 'bar', name: series.label, data: series.values,
        stack: stacked ? 'total' : undefined, areaStyle: data.chart.includes('area') ? {} : undefined, connectNulls: false }));
    const option: Option = {
      animation: false, color: ['#176b61', '#5386b3', '#d29b3c', '#b76b73', '#7567a6', '#679f66'],
      legend: { type: 'scroll', top: 8 }, tooltip: { trigger: data.chart === 'pie' || data.chart === 'scatter' ? 'item' : 'axis', renderMode: 'richText', confine: true },
      aria: { enabled: true, label: { description: `${data.name}。数値は下の表でも確認できます。` } },
      grid: { left: 65, right: 30, bottom: 70, top: 55, containLabel: true },
      series,
      ...(data.chart === 'pie' ? {} : data.chart === 'scatter' ? {
        xAxis: { type: data.scale.x === 'log' ? 'log' : 'value', name: data.x ?? undefined },
        yAxis: { type: data.scale.y === 'log' ? 'log' : 'value', name: data.y ?? undefined },
      } : {
        xAxis: horizontal ? { type: 'value' } : { type: 'category', data: categories, axisLabel: { hideOverlap: true } },
        // 横棒は先頭の項目を上に置き、デスクトップと数値表の順序を保つ。
        yAxis: horizontal ? { type: 'category', data: categories, inverse: true, axisLabel: { width: 150, overflow: 'truncate' } } : { type: 'value', name: data.y ?? undefined },
      }),
    };
    chart.setOption(option);
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(element.current);
    return () => { observer.disconnect(); chart.dispose(); };
  }, [data]);
  return <Box>
    {data.errors.map((error, i) => <Alert severity="warning" key={`error-${i}`} sx={{ mb: 1 }}>{error}</Alert>)}
    {data.notes.map((note, i) => <Alert severity="info" key={i} sx={{ mb: 1 }}>{note}</Alert>)}
    {!data.categories.length ? <Typography color="text.secondary">{data.errors.length ? '図に表示できる数値がありません。上の指摘を確認してください。' : '該当する対象はありません。'}</Typography> : <Box ref={element} role="img" aria-label={data.name} sx={{ height: 380, width: '100%' }} />}
    <Box component="details" sx={{ mt: 2 }}><Typography component="summary" sx={{ cursor: 'pointer' }}>図の数値を表で確認</Typography>
      <TableContainer><Table size="small" aria-label="図の数値"><TableHead><TableRow><TableCell>項目</TableCell>{data.chart === 'scatter' && <TableCell>{data.x}</TableCell>}{data.series.map((series, i) => <TableCell key={i}>{series.label}</TableCell>)}</TableRow></TableHead>
        <TableBody>{data.categories.map((category, i) => <TableRow key={category.key}><TableCell>{category.label}</TableCell>{data.chart === 'scatter' && <TableCell>{category.x ?? '—'}</TableCell>}{data.series.map((series, j) => <TableCell key={j}>{series.values[i] ?? '—'}</TableCell>)}</TableRow>)}</TableBody>
      </Table></TableContainer>
    </Box>
  </Box>;
}
