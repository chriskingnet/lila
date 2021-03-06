import { h } from 'snabbdom';
import { VNode } from 'snabbdom/vnode'
import { TagArray } from './interfaces';
import { renderClocks } from '../clocks';
import AnalyseCtrl from '../ctrl';
import { isFinished, findTag } from './studyChapters';

interface PlayerNames {
  white: string;
  black: string;
}

export default function(ctrl: AnalyseCtrl): VNode[] | undefined {
  const study = ctrl.study;
  if (!study) return;
  const tags = study.data.chapter.tags,
   playerNames = {
    white: findTag(tags, 'white')!,
    black: findTag(tags, 'black')!
  };
  if (!playerNames.white || !playerNames.black) return;
  const clocks = renderClocks(ctrl),
  ticking = !isFinished(study.data.chapter) && ctrl.turnColor();
  return (['white', 'black'] as Color[]).map(color => renderPlayer(tags, clocks, playerNames, color, ticking === color));
}

function renderPlayer(tags: TagArray[], clocks: [VNode, VNode] | undefined, playerNames: PlayerNames, color: Color, ticking: boolean): VNode {
  const title = findTag(tags, `${color}title`),
  elo = findTag(tags, `${color}elo`);
  return h(`div.player_bar.${color}`, {
    class: { ticking }
  }, [
    h('div.left', [
      h('span.color'),
      h('span.info', [
        title && h('span.title', title + ' '),
        h('span.name', playerNames[color]),
        elo && h('span.elo', elo)
      ])
    ]),
    clocks && clocks[color === 'white' ? 0 : 1]
  ]);
}
