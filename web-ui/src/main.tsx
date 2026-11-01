import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { createTheme, CssBaseline, ThemeProvider } from '@mui/material';
import { App } from './ui/App';
import './style.css';

const cache = createCache({ key: 'yde', nonce: document.querySelector<HTMLMetaElement>('meta[name="yde-style-nonce"]')?.content });
const queries = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: 'always' }, mutations: { retry: false } } });
const theme = createTheme({
  palette: { primary: { main: '#176b61' }, background: { default: '#f5f7f8', paper: '#ffffff' }, text: { primary: '#1f3042', secondary: '#566879' } },
  typography: { fontFamily: '"Segoe UI", "Meiryo", sans-serif', button: { textTransform: 'none', fontWeight: 600 } },
  shape: { borderRadius: 10 },
});
createRoot(document.getElementById('root')!).render(
  <React.StrictMode><CacheProvider value={cache}><ThemeProvider theme={theme}><CssBaseline />
    <QueryClientProvider client={queries}><BrowserRouter basename="/app"><App /></BrowserRouter></QueryClientProvider>
  </ThemeProvider></CacheProvider></React.StrictMode>,
);
