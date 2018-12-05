/*
 * java-gsc is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-gsc is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.gsc.core.operator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.gsc.core.Wallet;
import org.gsc.core.exception.BalanceInsufficientException;
import org.gsc.core.exception.ContractExeException;
import org.gsc.core.exception.ContractValidateException;
import org.gsc.core.wrapper.AccountWrapper;
import org.gsc.core.wrapper.AssetIssueWrapper;
import org.gsc.core.wrapper.TransactionResultWrapper;
import org.gsc.core.wrapper.utils.TransactionUtil;
import org.gsc.db.Manager;
import org.gsc.protos.Contract.AssetIssueContract;
import org.gsc.protos.Contract.AssetIssueContract.FrozenSupply;
import org.gsc.protos.Protocol.Account.Frozen;
import org.gsc.protos.Protocol.Transaction.Result.code;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
public class AssetIssueOperator extends AbstractOperator {

  AssetIssueOperator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  // AssetIssueContract
  @Override
  public boolean execute(TransactionResultWrapper ret) throws ContractExeException {
    long fee = calcFee();
    try {
      AssetIssueContract assetIssueContract = contract.unpack(AssetIssueContract.class);
      byte[] ownerAddress = assetIssueContract.getOwnerAddress().toByteArray();
      System.out.println(assetIssueContract.getDescription());
      AssetIssueWrapper assetIssueWrapper = new AssetIssueWrapper(assetIssueContract);
      /** start remove: Avoid TOKEN's duplicate name*/
      /*
      String name = new String(assetIssueWrapper.getName().toByteArray(),
          Charset.forName("UTF-8")); // getName().toStringUtf8()
      long order = 0;
      byte[] key = name.getBytes();
      // name + "_" + order;
      while (this.dbManager.getAssetIssueStore().get(key) != null) {
        order++;
        String nameKey = AssetIssueWrapper.createDbKeyString(name, order);
        key = nameKey.getBytes();
      }
      assetIssueWrapper.setOrder(order);
      */
      /** end */

      dbManager.getAssetIssueStore()
          .put(assetIssueWrapper.createDbKey(), assetIssueWrapper);

      // 1000 000000/1000000
      dbManager.adjustBalance(ownerAddress, -fee);
      dbManager.adjustBalance(dbManager.getAccountStore().getBlackhole().getAddress().toByteArray(),
          fee);//send to blackhole

      AccountWrapper accountWrapper = dbManager.getAccountStore().get(ownerAddress);
      List<FrozenSupply> frozenSupplyList = assetIssueContract.getFrozenSupplyList(); // 冻结Token的数量和冻结时间列表。
      Iterator<FrozenSupply> iterator = frozenSupplyList.iterator();
      long remainSupply = assetIssueContract.getTotalSupply(); // 发行总的token数量
      List<Frozen> frozenList = new ArrayList<>();
      long startTime = assetIssueContract.getStartTime();

      /**
       * message FrozenSupply {
       *     int64 frozen_amount = 1;
       *     int64 frozen_days = 2;
       *   }
       */
      while (iterator.hasNext()) {
        FrozenSupply next = iterator.next();
        long expireTime = startTime + next.getFrozenDays() * 86_400_000; // 24h
        Frozen newFrozen = Frozen.newBuilder()
            .setFrozenBalance(next.getFrozenAmount())
            .setExpireTime(expireTime)
            .build();
        frozenList.add(newFrozen);
        remainSupply -= next.getFrozenAmount();
      }

      accountWrapper.setAssetIssuedName(assetIssueWrapper.createDbKey());
      accountWrapper.addAsset(assetIssueWrapper.createDbKey(), remainSupply);
      accountWrapper.setInstance(accountWrapper.getInstance().toBuilder()
          .addAllFrozenSupply(frozenList).build());

      dbManager.getAccountStore().put(ownerAddress, accountWrapper);
      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    } catch (BalanceInsufficientException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    } catch (ArithmeticException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
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
    if (!this.contract.is(AssetIssueContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [AssetIssueContract],real type[" + contract
              .getClass() + "]");
    }
    final AssetIssueContract assetIssueContract;
    try {
      assetIssueContract = this.contract.unpack(AssetIssueContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }
    byte[] ownerAddress = assetIssueContract.getOwnerAddress().toByteArray();
    if (!Wallet.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid ownerAddress");
    }
    // todo add to AssetName repeat
    byte[] name = new String(assetIssueContract.getName().toByteArray(),
            Charset.forName("UTF-8")).getBytes();;
    if (this.dbManager.getAssetIssueStore().get(name) != null) {
      throw new ContractValidateException("AssetName repeat!");
    }
    if (!TransactionUtil.validAssetName(assetIssueContract.getName().toByteArray())) {
      throw new ContractValidateException("Invalid assetName");
    }
    if ((!assetIssueContract.getAbbr().isEmpty()) && !TransactionUtil
        .validAssetName(assetIssueContract.getAbbr().toByteArray())) {
      throw new ContractValidateException("Invalid abbreviation for token");
    }
    if (!TransactionUtil.validUrl(assetIssueContract.getUrl().toByteArray())) {
      throw new ContractValidateException("Invalid url");
    }
    if (!TransactionUtil
        .validAssetDescription(assetIssueContract.getDescription().toByteArray())) {
      throw new ContractValidateException("Invalid description");
    }

    if (assetIssueContract.getStartTime() == 0) {
      throw new ContractValidateException("Start time should be not empty");
    }
    if (assetIssueContract.getEndTime() == 0) {
      throw new ContractValidateException("End time should be not empty");
    }
    if (assetIssueContract.getEndTime() <= assetIssueContract.getStartTime()) {
      throw new ContractValidateException("End time should be greater than start time");
    }
    if (assetIssueContract.getStartTime() <= dbManager.getHeadBlockTimeStamp()) {
      throw new ContractValidateException("Start time should be greater than HeadBlockTime");
    }

    /*
    if (this.dbManager.getAssetIssueStore().get(assetIssueContract.getName().toByteArray())
        != null) {
      throw new ContractValidateException("Token exists");
    }
    */

    if (assetIssueContract.getTotalSupply() <= 0) {
      throw new ContractValidateException("TotalSupply must greater than 0!");
    }

    if (assetIssueContract.getGscNum() <= 0) {
      throw new ContractValidateException("GscNum must greater than 0!");
    }

    if (assetIssueContract.getNum() <= 0) {
      throw new ContractValidateException("Num must greater than 0!");
    }

    if (assetIssueContract.getPublicFreeAssetNetUsage() != 0) {
      throw new ContractValidateException("PublicFreeAssetNetUsage must be 0!");
    }

    if (assetIssueContract.getFrozenSupplyCount()
        > this.dbManager.getDynamicPropertiesStore().getMaxFrozenSupplyNumber()) {
      throw new ContractValidateException("Frozen supply list length is too long");
    }

    if (assetIssueContract.getFreeAssetNetLimit() < 0
        || assetIssueContract.getFreeAssetNetLimit() >=
        dbManager.getDynamicPropertiesStore().getOneDayNetLimit()) {
      throw new ContractValidateException("Invalid FreeAssetNetLimit");
    }

    if (assetIssueContract.getPublicFreeAssetNetLimit() < 0
        || assetIssueContract.getPublicFreeAssetNetLimit() >=
        dbManager.getDynamicPropertiesStore().getOneDayNetLimit()) {
      throw new ContractValidateException("Invalid PublicFreeAssetNetLimit");
    }

    long remainSupply = assetIssueContract.getTotalSupply();
    long minFrozenSupplyTime = dbManager.getDynamicPropertiesStore().getMinFrozenSupplyTime();
    long maxFrozenSupplyTime = dbManager.getDynamicPropertiesStore().getMaxFrozenSupplyTime();
    List<FrozenSupply> frozenList = assetIssueContract.getFrozenSupplyList();
    Iterator<FrozenSupply> iterator = frozenList.iterator();

    while (iterator.hasNext()) {
      FrozenSupply next = iterator.next();
      if (next.getFrozenAmount() <= 0) {
        throw new ContractValidateException("Frozen supply must be greater than 0!");
      }
      if (next.getFrozenAmount() > remainSupply) {
        throw new ContractValidateException("Frozen supply cannot exceed total supply");
      }
      if (!(next.getFrozenDays() >= minFrozenSupplyTime
          && next.getFrozenDays() <= maxFrozenSupplyTime)) {
        throw new ContractValidateException(
            "frozenDuration must be less than " + maxFrozenSupplyTime + " days "
                + "and more than " + minFrozenSupplyTime + " days");
      }
      remainSupply -= next.getFrozenAmount();
    }

    AccountWrapper accountWrapper = dbManager.getAccountStore().get(ownerAddress);
    if (accountWrapper == null) {
      throw new ContractValidateException("Account not exists");
    }

    if (!accountWrapper.getAssetIssuedName().isEmpty()) {
      throw new ContractValidateException("An account can only issue one asset");
    }

    if (accountWrapper.getBalance() < calcFee()) {
      throw new ContractValidateException("No enough balance for fee!");
    }
    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(AssetIssueContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return dbManager.getDynamicPropertiesStore().getAssetIssueFee();
  }

  public long calcUsage() {
    return 0;
  }
}
