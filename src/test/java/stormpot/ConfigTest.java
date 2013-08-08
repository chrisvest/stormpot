/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import org.junit.Before;
import org.junit.Test;

public class ConfigTest {
  Config<Poolable> config;
  
  @Before public void
  setUp() {
    config = new Config<Poolable>();
  }
  
  @Test public void
  sizeMustBeSettable() {
    config.setSize(123);
    assertTrue(config.getSize() == 123);
  }
  
  @Test public void
  allocatorMustBeSettable() {
    Allocator<?> allocator = new CountingAllocator();
    config.setAllocator(allocator);
    assertTrue(config.getAllocator() == allocator);
  }
  
  @Test public void
  mustHaveTimeBasedDeallocationRuleAsDefaul() {
    assertThat(config.getExpiration(),
        instanceOf(TimeSpreadExpiration.class));
  }
  
  @Test public void
  deallocationRuleMustBeSettable() {
    Expiration<Poolable> expectedRule = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) {
        return false;
      }
    };
    config.setExpiration(expectedRule);
    @SuppressWarnings("unchecked")
    Expiration<Poolable> actualRule =
        (Expiration<Poolable>) config.getExpiration();
    assertThat(actualRule, is(expectedRule));
  }
}
