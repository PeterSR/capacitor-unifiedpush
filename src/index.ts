import { registerPlugin } from '@capacitor/core';

import type { UnifiedPushPlugin } from './definitions';

const UnifiedPush = registerPlugin<UnifiedPushPlugin>('UnifiedPush', {
  web: () => import('./web').then((m) => new m.UnifiedPushWeb()),
});

export * from './definitions';
export { UnifiedPush };
