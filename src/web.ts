import { WebPlugin } from '@capacitor/core';

import type { UnifiedPushPlugin } from './definitions';

/** UnifiedPush is an Android mechanism. Every method rejects on the web. */
export class UnifiedPushWeb
  extends WebPlugin
  implements Omit<UnifiedPushPlugin, 'addListener' | 'removeAllListeners'>
{
  async getDistributors(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async getSavedDistributor(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async saveDistributor(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async register(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async unregister(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async getLastEndpoint(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async checkPermissions(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async requestPermissions(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async getPendingMessages(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async ackMessages(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async getPendingActions(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async ackActions(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async getPendingUnregistrations(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
  async ackUnregistrations(): Promise<never> {
    throw this.unimplemented('UnifiedPush is only available on Android.');
  }
}
