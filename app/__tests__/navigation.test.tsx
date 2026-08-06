/**
 * App screen toggle (Home ↔ Monitor). vision-camera is auto-mocked
 * (__mocks__/react-native-vision-camera.js) so this runs without a device;
 * with no camera permission the Monitor screen shows its permission prompt.
 */
import React from 'react';
import {Text} from 'react-native';
import ReactTestRenderer, {act} from 'react-test-renderer';
import App from '../App';

function allText(root: any): string {
  return root
    .findAllByType(Text)
    .map((t: any) => {
      const c = t.props.children;
      return Array.isArray(c) ? c.join('') : String(c ?? '');
    })
    .join(' | ');
}

test('點擊「開始監測」切換到監測畫面並要求相機/麥克風', async () => {
  let r: any;
  await act(() => {
    r = ReactTestRenderer.create(<App />);
  });
  const root = r.root;

  // Home is shown first.
  expect(allText(root)).toContain('開始監測');

  // Find the CTA label, then walk up to the pressable node that owns onPress.
  const label = root.findAll((n: any) => n.props?.children === '開始監測')[0];
  expect(label).toBeTruthy();
  let node: any = label;
  while (node && typeof node.props?.onPress !== 'function') {
    node = node.parent;
  }
  expect(node).toBeTruthy();
  await act(() => {
    node.props.onPress();
  });

  // Monitor screen requests camera + mic (mock reports no permission).
  expect(allText(root)).toContain('需要相機與麥克風權限');
});
