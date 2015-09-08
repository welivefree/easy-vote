package ballotserver.ws.client.service;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;
import javax.xml.ws.WebServiceException;

import org.bouncycastle.util.Arrays;

import sirs.framework.criptography.CriptoUtils;
import sirs.framework.exception.ExceptionParser;
import sirs.framework.exception.RemoteException;
import sirs.framework.ws.StubFactoryException;

import ballotserver.exceptions.BallotServerException;
import ballotserver.exceptions.InvalidMACException;
import ballotserver.views.BallotSheetView;
import ballotserver.views.CandidateView;
import ballotserver.ws.client.BallotServerStubFactory;
import ballotserver.ws.ties.BallotServerFault_Exception;
import ballotserver.ws.ties.BallotServerPortType;
import ballotserver.ws.ties.GetBallotSheetRequestType;
import ballotserver.ws.ties.GetBallotSheetResponseType;
import ballotserver.ws.ties.ServiceError_Exception;


public class GetBallotSheetService {

	private String endpoint;
	private SecretKey specialKey;
	private byte[] regSignedToken;
	private byte[] regToken;
	private byte[] tcSignedToken;
	private byte[] tcToken;
	
	public GetBallotSheetService(String endpoint, SecretKey specialKey,
			byte[] regSignedToken, byte[] regToken, byte[] tcSignedToken, byte[] tcToken){
		this.endpoint = endpoint;
		this.specialKey = specialKey;
		this.regSignedToken = regSignedToken;
		this.regToken = regToken;
		this.tcSignedToken = tcSignedToken;
		this.tcToken = tcToken;
	}
	
	public BallotSheetView execute() throws BallotServerException, RemoteException{
		try {
			BallotServerPortType port = BallotServerStubFactory.getInstance().getPort(this.endpoint);
			GetBallotSheetRequestType request = new GetBallotSheetRequestType();
			
			//integrity and authenticity:
			//using a MAC with the shared key and the SIRSFramework hashing function
			//that means hashing the message (parameters) along with the shared secret
			//encoding result for sending
			request.setMac(CriptoUtils.base64encode(CriptoUtils.computeDigest(
					this.regSignedToken, this.regToken, this.tcSignedToken, 
					this.tcToken, this.specialKey.getEncoded())));
			
			//confidenciality:
			//ciphering regSignedToken and tcSignedToken with shared symmetrical key
			//regToken and tcToken will be sent plain so that the BallotServer can
			//fetch the previously traded shared secret key
			//encoding result for sending
			
			request.setRegSignedToken(CriptoUtils.base64encode(
					CriptoUtils.cipherWithSymKey(this.regSignedToken, this.specialKey)));
			request.setRegToken(CriptoUtils.base64encode(this.regToken));
			request.setTcSignedToken(CriptoUtils.base64encode(
					CriptoUtils.cipherWithSymKey(this.tcSignedToken, this.specialKey)));
			request.setTcToken(CriptoUtils.base64encode(this.tcToken));
			
			GetBallotSheetResponseType response = port.getBallotSheet(request);
			
			//confidenciality:
			//deciphering the arguments candidatesUnparsed and the safeOffset with
			//the shared symmetrical key with the ballot server
			String candidatesUnparsed = new String(CriptoUtils.decipherWithSymKey(
					CriptoUtils.base64decode(response.getCandNamesAndIds()), this.specialKey));
			byte[] safeOffset = CriptoUtils.decipherWithSymKey(
					CriptoUtils.base64decode(response.getSafeOffset()), this.specialKey);
			Long electionId = new Long(new String(CriptoUtils.decipherWithSymKey(CriptoUtils.base64decode(
					response.getElectionId()), this.specialKey)));
			String question = new String(CriptoUtils.decipherWithSymKey(
					CriptoUtils.base64decode(response.getQuestion()), this.specialKey));
			
			//integrity and authenticity:
			//verifying the received MAC
			byte[] computedHash = CriptoUtils.computeDigest(
					candidatesUnparsed.getBytes(), safeOffset, electionId.toString().getBytes(), 
					question.getBytes(), this.specialKey.getEncoded());
			byte[] receivedHash = CriptoUtils.base64decode(response.getMac());
			if(!Arrays.areEqual(computedHash, receivedHash)){
				throw new InvalidMACException("Invalid MAC in webservice client: " + this.getClass().getName());
			}
			
			return new BallotSheetView(candidatesUnparsed, safeOffset, electionId, question);
			
		} catch (BallotServerFault_Exception e) {
			// remote domain exception
			BallotServerException ex = ExceptionParser.parse(e.getFaultInfo()
					.getFaultType(), e.getMessage());
			throw ex;
		}
		catch (ServiceError_Exception e) {
			// remote service error
			throw new RemoteException(e);
		}
		catch (StubFactoryException e) {
			throw new RemoteException(e);
		}
		catch (WebServiceException e) {
			throw new RemoteException(e);
		}
	}
	
}
