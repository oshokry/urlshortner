import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import App from './App.jsx';

const sample = {
  code: 'demo', shortUrl: 'http://localhost:8080/r/demo', url: 'https://example.com',
  clicks: 3, expiresAt: '2030-10-21T12:00:00Z', lastClickedAt: null, expired: false, disabled: false,
};
const reply = (data, status = 200) => ({ ok: status < 400, status, json: async () => data });
function setup() {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  render(<App />);
  return { user: userEvent.setup(), fetch };
}
async function lookup(user, fetch, link = sample) {
  fetch.mockResolvedValueOnce(reply(link));
  await user.type(screen.getByLabelText('Short code'), link.code);
  await user.click(screen.getByRole('button', { name: 'Refresh' }));
  await screen.findByText(link.url);
}

describe('React shortener workflows', () => {
  it('creates a link with form encoding and populates analytics', async () => {
    const { user, fetch } = setup(); fetch.mockResolvedValueOnce(reply(sample, 201));
    await user.type(screen.getByLabelText('Destination URL'), sample.url);
    await user.type(screen.getByLabelText(/Custom alias/), sample.code);
    await user.selectOptions(screen.getByLabelText('Expires after'), '3600');
    await user.click(screen.getByRole('button', { name: /Shorten link/ }));
    expect(await screen.findByRole('link')).toHaveAttribute('href', sample.shortUrl);
    expect(screen.getByLabelText('Short code')).toHaveValue(sample.code);
    const [path, options] = fetch.mock.calls[0];
    expect(path).toBe('/api/links'); expect(options.method).toBe('POST');
    expect(Object.fromEntries(options.body)).toEqual({ url: sample.url, alias: 'demo', ttlSeconds: '3600' });
  });
  it('copies the created link', async () => {
    const { user, fetch } = setup(); fetch.mockResolvedValueOnce(reply(sample, 201));
    const copy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    await user.type(screen.getByLabelText('Destination URL'), sample.url);
    await user.click(screen.getByRole('button', { name: /Shorten link/ }));
    await user.click(await screen.findByRole('button', { name: 'Copy link' }));
    expect(copy).toHaveBeenCalledWith(sample.shortUrl);
  });
  it('shows API errors without displaying a success result', async () => {
    const { user, fetch } = setup(); fetch.mockResolvedValueOnce(reply({ error: 'Alias already exists' }, 409));
    await user.type(screen.getByLabelText('Destination URL'), sample.url);
    await user.click(screen.getByRole('button', { name: /Shorten link/ }));
    expect(await screen.findByText('Alias already exists')).toBeVisible();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
  it('renews an expired link with the selected duration', async () => {
    const { user, fetch } = setup(); await lookup(user, fetch, { ...sample, expired: true });
    expect(screen.getByText('Expired')).toBeVisible();
    fetch.mockResolvedValueOnce(reply(sample));
    await user.selectOptions(screen.getByLabelText('Extend expiry by'), '3600');
    await user.click(screen.getByRole('button', { name: 'Extend expiry' }));
    expect(await screen.findByText('Active')).toBeVisible();
    const [path, options] = fetch.mock.calls[1];
    expect(path).toBe('/api/links/demo'); expect(options.method).toBe('PATCH');
    expect(options.body.get('ttlSeconds')).toBe('3600');
  });
  it('requires refresh after an uncertain extension and does not retry automatically', async () => {
    const { user, fetch } = setup(); await lookup(user, fetch);
    fetch.mockRejectedValueOnce(new Error('Network lost'));
    await user.click(screen.getByRole('button', { name: 'Extend expiry' }));
    expect(await screen.findByText(/Refresh to check the link before retrying/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Extend expiry' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Disable demo' })).toBeDisabled();
    expect(fetch).toHaveBeenCalledTimes(2);
    fetch.mockResolvedValueOnce(reply(sample));
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Extend expiry' })).toBeEnabled());
  });
  it('confirms disable and retains analytics; cancel sends nothing', async () => {
    const { user, fetch } = setup(); await lookup(user, fetch);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await user.click(screen.getByRole('button', { name: 'Disable demo' }));
    expect(fetch).toHaveBeenCalledTimes(1);
    confirm.mockReturnValue(true); fetch.mockResolvedValueOnce(reply(null, 204));
    await user.click(screen.getByRole('button', { name: 'Disable demo' }));
    expect(await screen.findByText('Disabled')).toBeVisible();
    expect(screen.getByText('3')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Link disabled' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Extend expiry' })).toBeDisabled();
    expect(fetch.mock.calls[1]).toEqual(['/api/links/demo', { method: 'DELETE' }]);
  });
  it('enables a disabled link with retained analytics and expiry', async () => {
    const { user, fetch } = setup(); await lookup(user, fetch, { ...sample, disabled: true, expired: true });
    fetch.mockResolvedValueOnce(reply({ ...sample, expired: true }));
    await user.click(screen.getByRole('button', { name: 'Enable demo' }));
    expect(await screen.findByText('Enabled. Extend expiry to restore redirects.')).toBeVisible();
    expect(screen.getByText('Expired')).toBeVisible();
    expect(screen.getByText('3')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Extend expiry' })).toBeEnabled();
    const [path, options] = fetch.mock.calls[1];
    expect(path).toBe('/api/links/demo'); expect(options.method).toBe('PATCH');
    expect(Object.fromEntries(options.body)).toEqual({ disabled: 'false' });
  });
  it('locks enable while pending and requires refresh after failure', async () => {
    const { user, fetch } = setup(); await lookup(user, fetch, { ...sample, disabled: true });
    let reject;
    fetch.mockReturnValueOnce(new Promise((_, fail) => { reject = fail; }));
    await user.click(screen.getByRole('button', { name: 'Enable demo' }));
    expect(screen.getByRole('button', { name: 'Enable demo' })).toBeDisabled();
    expect(screen.getByLabelText('Short code')).toBeDisabled();
    reject(new Error('Network lost'));
    expect(await screen.findByText(/Refresh to check the link before retrying/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Enable demo' })).toBeDisabled();
    fetch.mockResolvedValueOnce(reply({ ...sample, disabled: true }));
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enable demo' })).toBeEnabled());
    await user.type(screen.getByLabelText('Short code'), 'other');
    expect(screen.queryByRole('button', { name: 'Enable demo' })).not.toBeInTheDocument();
  });
  it.each([
    ['never-expiring', { expiresAt: null }], ['disabled', { disabled: true }],
  ])('prevents extension of %s links', async (_, fields) => {
    const { user, fetch } = setup(); await lookup(user, fetch, { ...sample, ...fields });
    expect(screen.getByRole('button', { name: 'Extend expiry' })).toBeDisabled();
  });
  it('invalidates loaded actions when the lookup code changes', async () => {
    const { user, fetch } = setup(); await lookup(user, fetch);
    await user.type(screen.getByLabelText('Short code'), 'other');
    expect(screen.getByRole('button', { name: 'Extend expiry' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Look up a link to disable it' })).toBeDisabled();
    expect(screen.queryByText(sample.url)).not.toBeInTheDocument();
  });
  it('locks lookup controls while a request is pending', async () => {
    const { user, fetch } = setup(); let resolve;
    fetch.mockReturnValueOnce(new Promise(done => { resolve = done; }));
    await user.type(screen.getByLabelText('Short code'), 'demo');
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(screen.getByLabelText('Short code')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeDisabled();
    resolve(reply(sample));
    await waitFor(() => expect(screen.getByLabelText('Short code')).toBeEnabled());
  });
  it('reports a missing backend when the proxy returns non-JSON', async () => {
    const { user, fetch } = setup();
    fetch.mockResolvedValueOnce({ ok: false, status: 502, json: async () => { throw new Error('Invalid JSON'); } });
    await user.type(screen.getByLabelText('Short code'), 'demo');
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText(/Check that the Java backend is running/)).toBeVisible();
  });
});
