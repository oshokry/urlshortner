async function request(path, options) {
  const response = await fetch(path, options);
  if (response.status === 204) return null;
  let data;
  try { data = await response.json(); }
  catch { throw new Error('The API did not return JSON. Check that the Java backend is running.'); }
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}
const linkPath = code => '/api/links/' + encodeURIComponent(code);
export const api = {
  list: () => request('/api/links'),
  create: values => request('/api/links', { method: 'POST', body: new URLSearchParams(values) }),
  stats: code => request(linkPath(code)),
  extend: (code, ttlSeconds) => request(linkPath(code), { method: 'PATCH', body: new URLSearchParams({ ttlSeconds }) }),
  disable: code => request(linkPath(code), { method: 'DELETE' }),
};
