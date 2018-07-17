package org.gsc.db;

import org.apache.commons.lang3.ArrayUtils;
import org.gsc.core.wrapper.BytesCapsule;
import org.gsc.core.exception.ItemNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RecentBlockStore extends GscStoreWithRevoking<BytesCapsule> {

  @Autowired
  private RecentBlockStore(@Value("recent-block") String dbName) {
    super(dbName);
  }

  @Override
  public void put(byte[] key, BytesCapsule item) {
    super.put(key, item);
  }

  @Override
  public BytesCapsule get(byte[] key) throws ItemNotFoundException {
    byte[] value = dbSource.getData(key);
    if (ArrayUtils.isEmpty(value)) {
      throw new ItemNotFoundException();
    }
    return new BytesCapsule(value);
  }

  @Override
  public boolean has(byte[] key) {
    byte[] value = dbSource.getData(key);
    return null != value;
  }
}