package mrwint.gbtasgen.segment.pokemon.fight;

import mrwint.gbtasgen.move.Move;
import mrwint.gbtasgen.segment.Segment;
import mrwint.gbtasgen.segment.pokemon.EflTextSegment;
import mrwint.gbtasgen.segment.pokemon.fight.EflKillEnemyMonSegment.CheckMoveOrderMetric;
import mrwint.gbtasgen.segment.pokemon.fight.EflKillEnemyMonSegment.EflEnemyMoveDesc;
import mrwint.gbtasgen.segment.util.SeqSegment;
import mrwint.gbtasgen.state.StateBuffer;

public class EflSwitchPokemonSegment extends SeqSegment {

	int monScrolls;
	EflEnemyMoveDesc enemyMoveDesc;

	public EflSwitchPokemonSegment(int monScrolls, EflEnemyMoveDesc enemyMoveDesc) {
	  this.monScrolls = monScrolls;
	  this.enemyMoveDesc = enemyMoveDesc;
	}
  @Override
  protected void execute() {
    seqEflButton(Move.RIGHT);
    seqEflButton(Move.A);
    if (monScrolls == 0) {
      seqEflSkipInput(1);
      seqEflButton(Move.A);
    } else {
      if (monScrolls == 1 || monScrolls == -1)
        seqEflSkipInput(1);
      seqEflScrollAF(monScrolls);
    }
    seqEflSkipInput(1);
    delayEfl(new SeqSegment() {
      @Override
      protected void execute() {
        seqEflButton(Move.A);
        seq(new EflTextSegment()); // enough
        seq(new EflTextSegment(Move.A, 0)); // go
        seqMetric(new CheckMoveOrderMetric(null, enemyMoveDesc.move, 0));
        seq(new Segment() {
          @Override
          public StateBuffer execute(StateBuffer in) {
            return enemyMoveDesc.segment.execute(in, 0);
          }
        }); // use and miss
      }
    });
    if (enemyMoveDesc.segment.getFinishSegment() != null)
    seq(enemyMoveDesc.segment.getFinishSegment());
  }
}
