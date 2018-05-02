#ifndef IROHA_PROTO_TRANSACTION_BUILDER_HPP
#define IROHA_PROTO_TRANSACTION_BUILDER_HPP

#include "backend/protobuf/transaction.hpp"


class ProtoTransactionBuilder {
 public:
  auto build() & {
    return shared_model::proto::Transaction(transaction_);
  }

  auto build() && {
    return shared_model::proto::Transaction(std::move(transaction_));
  }

  auto creatorAccountId(const shared_model::interface::types::AccountIdType& id) {
    ProtoTransactionBuilder b = std::move(*this);
    b.transaction_.mutable_payload()->set_creator_account_id(id);
    return b;
  }

  auto createdTime(shared_model::interface::types::TimestampType created_time) {
    ProtoTransactionBuilder b = std::move(*this);
    b.transaction_.mutable_payload()->set_created_time(created_time);
    return b;
  }

//  ProtoTransactionBuilder() = default;
//
//  ProtoTransactionBuilder(const ProtoTransactionBuilder &) = delete;
//  ProtoTransactionBuilder &operator=(const ProtoTransactionBuilder &) = delete;
//
//  ProtoTransactionBuilder(ProtoTransactionBuilder &&) = default;
//  ProtoTransactionBuilder &operator=(ProtoTransactionBuilder &&) = default;

 private:
  iroha::protocol::Transaction transaction_;
};

#endif //IROHA_PROTO_TRANSACTION_BUILDER_HPP
