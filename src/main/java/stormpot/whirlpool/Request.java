package stormpot.whirlpool;


class Request {
  boolean active = false;
  WSlot requestOp;
  WSlot response;
  Request next;
  int passCount;
  Thread thread;
}
