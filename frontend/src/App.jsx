import { useState } from 'react';
import CreateLink from './CreateLink.jsx';
import LinkAnalytics from './LinkAnalytics.jsx';
import LinkList from './LinkList.jsx';

export default function App() {
  const [selected, setSelected] = useState(null);
  const [selection, setSelection] = useState(0);
  const select = link => { setSelected(link); setSelection(value => value + 1); };
  return <main>
    <header><span className="brand">↗ shortly</span><span className="badge">REACT · JAVA 17</span></header>
    <section className="intro"><p className="eyebrow">LESS LINK. MORE SIGNAL.</p><h1>Long URLs.<br />Short stories.</h1>
      <p>Create a memorable link, set its lifetime, and watch the clicks come in.</p></section>
    <div className="grid">
      <CreateLink onCreated={select} />
      <LinkAnalytics key={selection} initialLink={selected} />
      <LinkList onSelect={select} />
    </div>
    <footer>Persistent links &amp; clicks · HTTP 302 redirects · No tracking cookies<br />
      <span>Local interview prototype. Analytics count redirect requests, not unique people.</span></footer>
  </main>;
}
