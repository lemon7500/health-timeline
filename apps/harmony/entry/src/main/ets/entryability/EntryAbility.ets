import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { HealthStore } from '../store/HealthStore';

export default class EntryAbility extends UIAbility {
  async onCreate(_want: Want, _launchParam: AbilityConstant.LaunchParam): Promise<void> {
    await HealthStore.instance.initialize(this.context);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index');
  }
}
