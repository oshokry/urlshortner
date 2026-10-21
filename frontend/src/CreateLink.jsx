import { useState } from 'react';
import { api } from './api.js';

export default function CreateLink({ onCreated }) {
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState(null);
  const [message, setMessage] = useState('');
  const [error, setError] = useState(false);
  async function create(event) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    setPending(true); setError(false); setMessage('Creating…'); setResult(null);
    try {
      const link = await api.create(values);
      setResult(link); onCreated(link); setMessage('Your link is ready.');
    } catch (failure) { setError(true); setMessage(failure.message); }
    finally { setPending(false); }
  }
  async function copy() {
    try { await navigator.clipboard.writeText(result.shortUrl); setMessage('Copied to clipboard.'); }
    catch { setMessage('Select and copy the short link above.'); }
  }
  return <section className="card">
    <h2>Create a short link</h2>
    <form onSubmit={create}>
      <label htmlFor="url">Destination URL</label>
      <input id="url" name="url" type="url" placeholder="https://example.com/a-very-long-story" required maxLength={2048} />
      <div className="row"><div>
        <label htmlFor="alias">Custom alias <small>optional</small></label>
        <input id="alias" name="alias" placeholder="my-demo" pattern={'[A-Za-z0-9_\\-]{4,32}'} maxLength={32} />
      </div><div>
        <label htmlFor="ttlSeconds">Expires after</label>
        <select id="ttlSeconds" name="ttlSeconds" defaultValue="">
          <option value="">Never</option><option value="60">1 minute · demo</option>
          <option value="3600">1 hour</option><option value="86400">1 day</option><option value="604800">1 week</option>
        </select>
      </div></div>
      <button id="submit" disabled={pending}>Shorten link ↗</button>
    </form>
    <p id="message" role="status" className={error ? 'error' : ''}>{message}</p>
    {result && <div id="result"><span className="eyebrow">YOUR SHORT LINK</span>
      <a id="short" href={result.shortUrl} target="_blank" rel="noopener noreferrer">{result.shortUrl}</a>
      <button className="secondary" onClick={copy}>Copy link</button>
    </div>}
  </section>;
}
