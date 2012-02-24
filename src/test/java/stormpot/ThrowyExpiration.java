package stormpot;

final class ThrowyExpiration implements
    Expiration<Poolable> {
  public boolean hasExpired(SlotInfo<? extends Poolable> info) {
    throw new SomeRandomRuntimeException();
  }
}