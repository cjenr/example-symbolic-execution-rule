import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
public class MyClass {

  Lock l1 = new ReentrantLock();
  Lock l2 = new ReentrantLock();
  Object a = null;

  public void acquireLock() {
    Lock local = new ReentrantLock();
    local.lock();  // Noncompliant {{Unlock this lock along all executions paths of this method.}}
    l1.lock();
  }
}
