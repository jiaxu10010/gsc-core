package org.gsc.core.operator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.gsc.common.utils.StringUtil;
import org.gsc.core.Wallet;
import org.gsc.core.wrapper.AccountWrapper;
import org.gsc.core.wrapper.TransactionResultWrapper;
import org.gsc.db.Manager;
import org.gsc.db.StorageMarket;
import org.gsc.core.exception.ContractExeException;
import org.gsc.core.exception.ContractValidateException;
import org.gsc.protos.Contract.BuyStorageContract;
import org.gsc.protos.Protocol.Transaction.Result.code;

@Slf4j
public class BuyStorageOperator extends AbstractOperator {

  private StorageMarket storageMarket;

  BuyStorageOperator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    storageMarket = new StorageMarket(dbManager);
  }

  @Override
  public boolean execute(TransactionResultWrapper ret) throws ContractExeException {
    long fee = calcFee();
    final BuyStorageContract buyStorageContract;
    try {
      buyStorageContract = contract.unpack(BuyStorageContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }

    AccountWrapper accountWrapper = dbManager.getAccountStore()
        .get(buyStorageContract.getOwnerAddress().toByteArray());
    long quant = buyStorageContract.getQuant();

    storageMarket.buyStorage(accountWrapper, quant);

    ret.setStatus(fee, code.SUCESS);

    return true;
  }


  @Override
  public boolean validate() throws ContractValidateException {
    if (this.contract == null) {
      throw new ContractValidateException("No contract!");
    }
    if (this.dbManager == null) {
      throw new ContractValidateException("No dbManager!");
    }
    if (!contract.is(BuyStorageContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [BuyStorageContract],real type[" + contract
              .getClass() + "]");
    }

    final BuyStorageContract buyStorageContract;
    try {
      buyStorageContract = this.contract.unpack(BuyStorageContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }
    byte[] ownerAddress = buyStorageContract.getOwnerAddress().toByteArray();
    if (!Wallet.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }

    AccountWrapper accountWrapper = dbManager.getAccountStore().get(ownerAddress);
    if (accountWrapper == null) {
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
      throw new ContractValidateException(
          "Account[" + readableOwnerAddress + "] not exists");
    }

    long quant = buyStorageContract.getQuant();
    if (quant <= 0) {
      throw new ContractValidateException("quantity must be positive");
    }

    if (quant < 1000_000L) {
      throw new ContractValidateException("quantity must be larger than 1TRX");
    }

    if (quant > accountWrapper.getBalance()) {
      throw new ContractValidateException("quantity must be less than accountBalance");
    }
    long storage_bytes = storageMarket.tryBuyStorage(quant);
    if (storage_bytes < 1L) {
      throw new ContractValidateException(
          "storage_bytes must be larger than 1,current storage_bytes[" + storage_bytes + "]");
    }

//    long storageBytes = storageMarket.exchange(quant, true);
//    if (storageBytes > dbManager.getDynamicPropertiesStore().getTotalStorageReserved()) {
//      throw new ContractValidateException("storage is not enough");
//    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(BuyStorageContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
