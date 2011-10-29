package com.eucalyptus.cluster.callback;

import java.util.List;
import java.util.NoSuchElementException;
import org.apache.log4j.Logger;
import com.eucalyptus.cluster.Cluster;
import com.eucalyptus.cluster.Cluster.State;
import com.eucalyptus.component.Component;
import com.eucalyptus.component.ServiceChecks;
import com.eucalyptus.component.ServiceChecks.CheckException;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.empyrean.DescribeServicesResponseType;
import com.eucalyptus.empyrean.DescribeServicesType;
import com.eucalyptus.empyrean.DisableServiceType;
import com.eucalyptus.empyrean.ServiceStatusType;
import com.eucalyptus.util.async.AsyncRequests;
import com.eucalyptus.util.async.SubjectMessageCallback;
import com.eucalyptus.util.fsm.Automata;

public class ServiceStateCallback extends SubjectMessageCallback<Cluster, DescribeServicesType, DescribeServicesResponseType> {
  private static Logger LOG = Logger.getLogger( ServiceStateCallback.class );
  
  public ServiceStateCallback( ) {
    this.setRequest( new DescribeServicesType( ) );
  }
  
  @Override
  public void fire( DescribeServicesResponseType msg ) {
    List<ServiceStatusType> serviceStatuses = msg.getServiceStatuses( );
    if ( serviceStatuses.isEmpty( ) ) {
      throw new NoSuchElementException( "Failed to find service info for cluster: " + this.getSubject( ).getConfiguration( ) );
    } else {
      ServiceConfiguration config = this.getSubject( ).getConfiguration( );
      for ( ServiceStatusType status : serviceStatuses ) {
        if ( config.getName( ).equals( status.getServiceId( ).getName( ) ) ) {
          LOG.debug( "Found service info: " + status );
          Component.State serviceState = Component.State.valueOf( status.getLocalState( ) );
          Component.State localState = this.getSubject( ).getConfiguration( ).lookupState( );
          Component.State proxyState = this.getSubject( ).getStateMachine( ).getState( ).proxyState( );
          CheckException ex = ServiceChecks.chainCheckExceptions( ServiceChecks.Functions.statusToCheckExceptions( this.getRequest( ).getCorrelationId( ) ).apply( status ) );
          if ( Component.State.NOTREADY.equals( serviceState ) ) {
            throw new IllegalStateException( ex );
          } else if ( Component.State.ENABLED.equals( serviceState ) && Component.State.DISABLED.ordinal( ) >= proxyState.ordinal( ) ) {
            try {
              AsyncRequests.newRequest( new DisableServiceCallback( this.getSubject( ) ) ).sendSync( this.getSubject( ).getConfiguration( ) );
            } catch ( Exception ex1 ) {
              LOG.error( ex1, ex1 );
            }
          } else if ( Component.State.LOADED.equals( serviceState ) && Component.State.NOTREADY.ordinal( ) >= proxyState.ordinal( ) ) {
            try {
              AsyncRequests.newRequest( new StartServiceCallback( this.getSubject( ) ) ).sendSync( this.getSubject( ).getConfiguration( ) );
            } catch ( Exception ex1 ) {
              LOG.error( ex1, ex1 );
            }
          } else if ( Component.State.NOTREADY.ordinal( ) < serviceState.ordinal( ) ) {
            this.getSubject( ).clearExceptions( );
          }
        } else {
          LOG.debug( "Found service info: " + status );
        }
      }
    }
  }
  
  @Override
  public void fireException( Throwable t ) {
    LOG.error( t, t );
  }
  
}
