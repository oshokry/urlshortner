import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import App from './App.jsx';
import LinkList from './LinkList.jsx';

const active = { code: 'alpha', url: 'https://example.com/alpha', clicks: 2, expiresAt: '2030-01-01T00:00:00Z', disabled: false, expired: false };
const disabled = { ...active, code: 'beta', disabled: true, expired: true, expiresAt: null };
const expired = { ...active, code: 'older', expired: true };
const reply = (data, status = 200) => ({ status, ok: status < 400, json: async () => data });
function setup(component = <LinkList onSelect={vi.fn()} />) {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  render(component);
  return { user: userEvent.setup(), fetch };
}
async function load(user, fetch, links) {
  fetch.mockResolvedValueOnce(reply(links));
  await user.click(screen.getByRole('button', { name: 'Refresh links' }));
}

describe('Saved links', () => {
  it('loads an empty collection on demand', async () => {
    const { user, fetch } = setup();
    expect(fetch).not.toHaveBeenCalled();
    await load(user, fetch, []);
    expect(await screen.findByText('No links yet. Create your first short link.')).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/links', undefined);
  });
  it('renders all statuses, counts, destinations, expiry and the API order', async () => {
    const { user, fetch } = setup();
    await load(user, fetch, [active, disabled, expired]);
    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1);
    expect(rows.map(row => within(row).getByRole('button').textContent)).toEqual(['alpha', 'beta', 'older']);
    expect(within(rows[0]).getByText('Active')).toBeVisible();
    expect(within(rows[1]).getByText('Disabled')).toBeVisible();
    expect(within(rows[1]).getByText('Never')).toBeVisible();
    expect(within(rows[2]).getByText('Expired')).toBeVisible();
    expect(within(rows[0]).getByText('2')).toBeVisible();
    expect(within(rows[0]).getByText(active.url)).toBeVisible();
  });
  it('replaces stale rows on refresh and offers retry after failure', async () => {
    const { user, fetch } = setup(); await load(user, fetch, [active]);
    fetch.mockResolvedValueOnce(reply({ error: 'Temporary failure' }, 503));
    await user.click(screen.getByRole('button', { name: 'Refresh links' }));
    expect(await screen.findByText('Temporary failure')).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    await load(user, fetch, [disabled]);
    expect(await screen.findByRole('button', { name: 'Inspect beta' })).toBeVisible();
    expect(screen.queryByText('Temporary failure')).not.toBeInTheDocument();
  });
  it('loads fresh metadata when selecting and reselecting the same code', async () => {
    const { user, fetch } = setup(<App />); await load(user, fetch, [active]);
    fetch.mockResolvedValueOnce(reply({ ...active, clicks: 9 }));
    await user.click(await screen.findByRole('button', { name: 'Inspect alpha' }));
    expect(screen.getByLabelText('Short code')).toHaveValue('alpha');
    expect(await screen.findByText('9')).toBeVisible();
    fetch.mockResolvedValueOnce(reply({ ...active, clicks: 10, disabled: true }));
    await user.click(screen.getByRole('button', { name: 'Inspect alpha' }));
    expect(await screen.findByText('10')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Link disabled' })).toBeDisabled();
    expect(fetch.mock.calls[1][0]).toBe('/api/links/alpha');
  });
  it('keeps the list and reports failed selection without replacing analytics', async () => {
    const onSelect = vi.fn(); const { user, fetch } = setup(<LinkList onSelect={onSelect} />);
    await load(user, fetch, [active]); fetch.mockRejectedValueOnce(new Error('Network lost'));
    await user.click(await screen.findByRole('button', { name: 'Inspect alpha' }));
    expect(await screen.findByText('Network lost')).toBeVisible();
    expect(screen.getByRole('table')).toBeVisible();
    expect(onSelect).not.toHaveBeenCalled();
  });
  it('prevents overlapping list and selection requests while loading', async () => {
    const { user, fetch } = setup(); await load(user, fetch, [active]); let resolve;
    fetch.mockReturnValueOnce(new Promise(done => { resolve = done; }));
    await user.click(await screen.findByRole('button', { name: 'Inspect alpha' }));
    expect(screen.getByRole('button', { name: 'Inspect alpha' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Refresh links' })).toBeDisabled();
    resolve(reply(active));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh links' })).toBeEnabled());
  });
});
