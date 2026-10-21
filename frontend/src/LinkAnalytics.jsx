import { useState } from 'react';
import { api } from './api.js';

const date = value => value ? new Date(value).toLocaleString() : 'Never';

export default function LinkAnalytics({ initialLink }) {
  const [code, setCode] = useState(initialLink?.code || '');
  const [link, setLink] = useState(initialLink);
  const [duration, setDuration] = useState('86400');
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState(false);
  const actionable = link && !pending && !uncertain && !link.disabled;

  async function lookup(event) {
    event.preventDefault(); setPending(true); setError(false); setMessage('Loading…');
    try {
      setLink(await api.stats(code.trim())); setUncertain(false); setMessage('Updated just now.');
    } catch (failure) { setLink(null); setError(true); setMessage(failure.message); }
    finally { setPending(false); }
  }
  async function mutate(action) {
    if (!actionable) return;
    if (action === 'disable' && !window.confirm(`Permanently disable ${link.code}?`)) return;
    setPending(true); setError(false);
    try {
      if (action === 'extend') {
        setLink(await api.extend(link.code, duration));
        setMessage('Expiry extended. Your short link is unchanged.');
      } else {
        await api.disable(link.code);
        setLink({ ...link, disabled: true });
        setMessage('Disabled. Analytics are retained.');
      }
    } catch (failure) {
      // A lost response may follow a committed mutation. Require an explicit refresh.
      setUncertain(true); setError(true);
      setMessage(failure.message + ' Refresh to check the link before retrying.');
    } finally { setPending(false); }
  }
  return <section className="card">
    <h2>Link analytics</h2>
    <form onSubmit={lookup}>
      <label htmlFor="code">Short code</label><div className="lookup">
        <input id="code" value={code} disabled={pending} placeholder="Paste the code here" required
          pattern={'[A-Za-z0-9_\\-]{4,32}'} maxLength={32} onChange={event => {
            setCode(event.target.value); setLink(null); setMessage(''); setUncertain(false);
          }} />
        <button className="secondary" disabled={pending}>Refresh</button>
      </div>
    </form>
    <div className="metrics"><div><strong>{link?.clicks ?? '—'}</strong><span>Total redirects</span></div>
      <div><strong className="state">{!link ? '—' : link.disabled ? 'Disabled' : link.expired ? 'Expired' : 'Active'}</strong><span>Link status</span></div></div>
    <dl><dt>Destination</dt><dd>{link?.url || 'Create or look up a link to get started.'}</dd>
      <dt>Expires</dt><dd>{link ? date(link.expiresAt) : '—'}</dd>
      <dt>Last clicked</dt><dd>{link?.lastClickedAt ? date(link.lastClickedAt) : 'No clicks yet'}</dd></dl>
    <form onSubmit={event => { event.preventDefault(); mutate('extend'); }}>
      <label htmlFor="extendSeconds">Extend expiry by</label><div className="lookup">
        <select id="extendSeconds" value={duration} disabled={pending} onChange={event => setDuration(event.target.value)}>
          <option value="60">1 minute</option><option value="3600">1 hour</option>
          <option value="86400">1 day</option><option value="604800">1 week</option>
        </select><button disabled={!actionable || !link?.expiresAt}>Extend expiry</button>
      </div>
    </form>
    <p id="expiry-help">{link?.disabled ? 'Disabled links cannot be extended.' : link && !link.expiresAt ? 'This link never expires.' : 'Adds time to the current expiry, or from now if expired.'}</p>
    <p id="stats-message" role="status" className={error ? 'error' : ''}>{message}</p>
    <p id="disable-help">Disabling permanently stops redirects. Your click history is kept.</p>
    <button className="danger" aria-describedby="disable-help" disabled={!actionable} onClick={() => mutate('disable')}>
      {!link ? 'Look up a link to disable it' : link.disabled ? 'Link disabled' : `Disable ${link.code}`}
    </button>
  </section>;
}
