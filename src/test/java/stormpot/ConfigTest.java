/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class ConfigTest {
  private Config<Poolable> config;
  
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
  defaultAllocatorIsNull() {
    assertThat(config.getAllocator(), is(nullValue()));
  }

  @Test public void
  defaultReallocatorIsNull() {
    assertThat(config.getReallocator(), is(nullValue()));
  }

  @Test public void
  mustAdaptAllocatorsToReallocators() {
    Allocator<GenericPoolable> allocator = new CountingAllocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    Reallocator<GenericPoolable> reallocator = cfg.getReallocator();
    ReallocatingAdaptor<GenericPoolable> adaptor =
        (ReallocatingAdaptor<GenericPoolable>) reallocator;
    assertThat(adaptor.unwrap(), sameInstance(allocator));
  }

  @Test public void
  mustNotReadaptConfiguredReallocators() {
    Reallocator<Poolable> expected = new ReallocatingAdaptor<Poolable>(null);
    config.setAllocator(expected);
    Reallocator<Poolable> actual = config.getReallocator();
    assertThat(actual, sameInstance(expected));
  }

  @Test public void
  allocatorMustBeSettable() {
    Allocator<GenericPoolable> allocator = new CountingAllocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    assertThat(cfg.getAllocator(), sameInstance(allocator));
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
