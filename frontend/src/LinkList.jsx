import { useState } from 'react';
import { api } from './api.js';

export default function LinkList({ onSelect }) {
  const [links, setLinks] = useState(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function load() {
    setPending(true); setError(''); setLinks(null);
    try { setLinks(await api.list()); }
    catch (failure) { setError(failure.message); }
    finally { setPending(false); }
  }
  async function inspect(code) {
    setPending(true); setError('');
    try { onSelect(await api.stats(code)); }
    catch (failure) { setError(failure.message); }
    finally { setPending(false); }
  }
  return <section className="card saved-links" aria-labelledby="links-title">
    <h2 id="links-title">Saved links</h2>
    <p>Newest first. Select a code to load its analytics in Link analytics. Refresh to update counts and status.</p>
    <button className="secondary" disabled={pending} onClick={load}>Refresh links</button>
    <p role="status" className={error ? 'error' : ''}>{pending ? 'Loading…' : error || (links ? `${links.length} saved links.` : 'Refresh links to load saved links.')}</p>
    {links?.length === 0 && <p>No links yet. Create your first short link.</p>}
    {!!links?.length && <div className="links-table" role="region" aria-label="Saved links table" tabIndex={0}>
      <table><thead><tr><th scope="col">Code</th><th scope="col">Destination</th><th scope="col">Clicks</th><th scope="col">Expires</th><th scope="col">Status</th><th scope="col">Visit</th></tr></thead>
        <tbody>{links.map(link => <tr key={link.code}>
          <td><button className="secondary" aria-label={`Inspect ${link.code}`} disabled={pending} onClick={() => inspect(link.code)}>{link.code}</button></td>
          <td>{link.url}</td><td>{link.clicks}</td><td>{link.expiresAt ? new Date(link.expiresAt).toLocaleString() : 'Never'}</td>
          <td>{link.disabled ? 'Disabled' : link.expired ? 'Expired' : 'Active'}</td>
          <td><a href={link.shortUrl} target="_blank" rel="noopener noreferrer">Visit</a></td>
        </tr>)}</tbody>
      </table>
    </div>}
  </section>;
}
