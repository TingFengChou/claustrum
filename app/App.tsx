/**
 * claustrum — on-device (edge AI) perception brain.
 * Real-time streaming recognition is the north star (ADR-0005).
 * The camera is a proactive guardian, not an after-the-fact recorder (ADR-0006).
 *
 * @format
 */

import React, {useState} from 'react';
import HomeScreen from './src/screens/HomeScreen';
import MonitorScreen from './src/screens/MonitorScreen';

function App(): React.JSX.Element {
  const [screen, setScreen] = useState<'home' | 'monitor'>('home');
  return screen === 'monitor' ? (
    <MonitorScreen onBack={() => setScreen('home')} />
  ) : (
    <HomeScreen onStartMonitoring={() => setScreen('monitor')} />
  );
}

export default App;
