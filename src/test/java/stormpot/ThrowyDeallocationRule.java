package stormpot;

final class ThrowyDeallocationRule implements
    DeallocationRule<Poolable> {
  public boolean isInvalid(SlotInfo<? extends Poolable> info) {
    throw new SomeRandomRuntimeException();
  }
}