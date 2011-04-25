package stormpot.whirlpool;

/*
 * 1. Write the invocation opcode and parameters[1] (if any) of the method m to
 *    be applied sequentially to the shared object in the request field of your
 *    thread local publication record (there is no need to use a load-store
 *    memory barrier). The request field will later be used to receive[2] the
 *    response. If your thread local publication record is marked as active
 *    continue to step 2, otherwise continue to step 5.
 * 
 * 2. Check if the global lock is taken. If so (another thread is an active
 *    combiner), spin on the request field waiting for a response to the
 *    invocation (one can add a yield at this point to allow other threads on
 *    the same core to run). Once in a while, while spinning, check if the lock
 *    is still taken and that your record is active. If your record is
 *    inactive proceed to step 5. Once the response is available, reset the
 *    request field to null and return the response.
 * 
 * 3. If the lock is not taken, attempt to acquire it and become a combiner.
 *    If you fail, return to spinning in step 2.
 * 
 * 4. Otherwise, you hold the lock and are a combiner.
 *    • Increment the combining pass count by one.
 *    • Execute a scanCombineApply() by traversing the publication list from
 *      the head, combining all non-null method call invocations, setting the
 *      age of each of these records to the current count, applying the
 *      combined method calls to the structure D, and returning responses to
 *      all the invocations. As we explain later, this traversal is
 *      guaranteed to be wait-free.
 *    • If the count is such that a cleanup needs to be performed, traverse
 *      the publication list from the head. Starting from the second item (as
 *      we explain below, we always leave the item pointed to by the head in
 *      the list), remove from the publication list all records whose age is
 *      much smaller than the current count. This is done by removing the node
 *      and marking it as inactive.
 *    • Release the lock.
 * 
 * 5. If you have no thread local publication record allocate one, marked as
 *    active. If you already have one marked as inactive, mark it as active.
 *    Execute a store-load memory barrier. Proceed to insert the record into
 *    the list with a successful CAS to the head. Then proceed to step 1.
 * 
 * [1]: This must be a single atomic write, in order to preserve correct
 * ordering. Otherwise, the combiner thread might see the opcode before the
 * parameters are written.
 * In our case, we need to support 3 operations: Release a slot, claim a slot
 * and retrieve a dead slot. We encode the opcode by the type of the object in
 * the request field. If the object is a Slot, then we are releasing it. If
 * the request field is the "claim" value of the Take enum, then we are doing
 * a claim. If the request field is the "relieve" value of the Take enum, then
 * we are removing a dead slot from the queue for the purpose of reallocating
 * it. Otherwise, if the request field is null, then we take no action.
 * 
 * [2]: We are going to have a separate response field for this. The combiner
 * thread will write a null to the request field, once it has completed the
 * request. And the requester will write a null to the response field once it
 * has read it. The "claim" and "relieve" methods will both receive a slot
 * reference in the response field when the combiner thread has completed their
 * requests. A release call will receive a non-null value.
 */
public class PublistTest {
  
  // make sure we have a request in the list
  // try to take the lock
  // become combiner if successful
  // spin-wait for the result if not
}
