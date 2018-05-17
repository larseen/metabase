/* @flow */

import React from "react";
import { connect } from "react-redux";
import _ from "underscore";

import entityType from "./EntityType";
import LoadingAndErrorWrapper from "metabase/components/LoadingAndErrorWrapper";

export type Props = {
  entityType?: string,
  loadingAndErrorWrapper: boolean,
  children: (props: RenderProps) => ?React$Element<any>,
};

export type RenderProps = {
  list: ?(any[]),
  loading: boolean,
  error: ?any,
};

@entityType()
@connect((state, { entityDef, entityQuery }) => ({
  list: entityDef.selectors.getList(state, { entityQuery }),
  loading: entityDef.selectors.getLoading(state, { entityQuery }),
  error: entityDef.selectors.getError(state, { entityQuery }),
}))
export default class EntitiesListLoader extends React.Component {
  props: Props;

  static defaultProps = {
    loadingAndErrorWrapper: true,
    entityQuery: null,
    reload: false,
  };

  componentWillMount() {
    // $FlowFixMe: provided by @connect
    this.props.fetchList(this.props.entityQuery, this.props.reload);
  }

  componentWillReceiveProps(nextProps: Props) {
    if (!_.isEqual(nextProps.entityQuery, this.props.entityQuery)) {
      nextProps.fetchList(nextProps.entityQuery, nextProps.reload);
    }
  }

  reload = () => {
    this.props.fetchList(this.props.entityQuery, true);
  };

  renderChildren = () => {
    // $FlowFixMe: provided by @connect
    const { children, list, loading, error } = this.props;
    return children({ list, loading, error, reload: this.reload });
  };

  render() {
    // $FlowFixMe: provided by @connect
    const { loading, error, loadingAndErrorWrapper } = this.props;
    return loadingAndErrorWrapper ? (
      <LoadingAndErrorWrapper
        loading={loading}
        error={error}
        children={this.renderChildren}
      />
    ) : (
      this.renderChildren()
    );
  }
}
