import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './style.css';
class Boundary extends React.Component<React.PropsWithChildren, {error:boolean}> {
  state={error:false};static getDerivedStateFromError(){return {error:true};}
  render(){return this.state.error ? <main className="fatal"><h1>Something went wrong</h1><p>Your originals are still in Google Drive.</p><button onClick={()=>location.reload()}>Reload Photos App</button></main>:this.props.children;}
}
createRoot(document.getElementById('root')!).render(<React.StrictMode><Boundary><App/></Boundary></React.StrictMode>);
